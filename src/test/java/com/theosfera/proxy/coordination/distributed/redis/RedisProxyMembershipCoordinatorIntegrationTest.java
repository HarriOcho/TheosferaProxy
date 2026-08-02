package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipAcquireResult;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import com.theosfera.proxy.coordination.ProxyMembershipRenewResult;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisProxyMembershipCoordinatorIntegrationTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    private static final ProxyInstanceIdentity FIRST =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString("11111111-1111-1111-1111-111111111111")
            );

    private static final ProxyInstanceIdentity SECOND_SAME_NAME =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString("22222222-2222-2222-2222-222222222222")
            );

    private static GenericContainer<?> redis;
    private static RedisClient firstClient;
    private static RedisClient secondClient;
    private static StatefulRedisConnection<String, String> firstConnection;
    private static StatefulRedisConnection<String, String> secondConnection;

    private RedisProxyMembershipCoordinator firstCoordinator;
    private RedisProxyMembershipCoordinator secondCoordinator;

    @BeforeAll
    static void startRedis() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            String message = "Docker is not available for Redis Testcontainers";

            if (RedisTestcontainersSupport.shouldFailWhenDockerUnavailable()) {
                throw new IllegalStateException(message);
            }

            Assumptions.assumeTrue(false, message);
        }

        redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        redis.start();

        String redisUri = "redis://"
                + redis.getHost()
                + ":"
                + redis.getMappedPort(6379);

        firstClient = RedisClient.create(redisUri);
        secondClient = RedisClient.create(redisUri);
        firstConnection = firstClient.connect();
        secondConnection = secondClient.connect();
    }

    @AfterAll
    static void stopRedis() {
        if (firstConnection != null) {
            firstConnection.close();
        }
        if (secondConnection != null) {
            secondConnection.close();
        }
        if (firstClient != null) {
            firstClient.shutdown();
        }
        if (secondClient != null) {
            secondClient.shutdown();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        await(firstConnection.async().flushall());

        firstCoordinator = new RedisProxyMembershipCoordinator(
                firstConnection.async(),
                Duration.ofSeconds(15)
        );
        secondCoordinator = new RedisProxyMembershipCoordinator(
                secondConnection.async(),
                Duration.ofSeconds(15)
        );
    }

    @Test
    void sameProxyNameCannotHaveTwoValidOwners() throws Exception {
        ProxyMembershipAcquireResult first = await(
                firstCoordinator.acquire(FIRST)
        );
        assertEquals(ProxyMembershipAcquireResult.Status.ACQUIRED, first.status());

        ProxyMembershipAcquireResult repeated = await(
                firstCoordinator.acquire(FIRST)
        );
        assertEquals(
                ProxyMembershipAcquireResult.Status.ALREADY_OWNED,
                repeated.status()
        );
        assertEquals(
                first.lease().orElseThrow().fencingToken(),
                repeated.lease().orElseThrow().fencingToken()
        );

        ProxyMembershipAcquireResult competing = await(
                secondCoordinator.acquire(SECOND_SAME_NAME)
        );
        assertEquals(
                ProxyMembershipAcquireResult.Status.OWNED_BY_OTHER_INCARNATION,
                competing.status()
        );
        assertTrue(competing.lease().isEmpty());
    }

    @Test
    void releaseIsExactAndReacquireIncreasesFencing() throws Exception {
        ProxyMembershipLease firstLease = await(
                firstCoordinator.acquire(FIRST)
        ).lease().orElseThrow();

        ProxyMembershipLease stale = new ProxyMembershipLease(
                FIRST,
                firstLease.fencingToken() + 1
        );

        assertFalse(await(firstCoordinator.releaseIfOwned(stale)));
        assertTrue(await(firstCoordinator.releaseIfOwned(firstLease)));

        ProxyMembershipLease secondLease = await(
                secondCoordinator.acquire(SECOND_SAME_NAME)
        ).lease().orElseThrow();

        assertTrue(secondLease.fencingToken() > firstLease.fencingToken());
    }

    @Test
    void renewRequiresExactOwnerAndFencing() throws Exception {
        ProxyMembershipLease lease = await(
                firstCoordinator.acquire(FIRST)
        ).lease().orElseThrow();

        ProxyMembershipRenewResult renewed = await(
                firstCoordinator.renew(lease)
        );
        assertEquals(ProxyMembershipRenewResult.Status.RENEWED, renewed.status());
        assertEquals(lease, renewed.lease().orElseThrow());

        ProxyMembershipRenewResult otherIncarnation = await(
                secondCoordinator.renew(
                        new ProxyMembershipLease(
                                SECOND_SAME_NAME,
                                lease.fencingToken()
                        )
                )
        );
        assertEquals(
                ProxyMembershipRenewResult.Status.NOT_OWNER,
                otherIncarnation.status()
        );

        ProxyMembershipRenewResult wrongFencing = await(
                firstCoordinator.renew(
                        new ProxyMembershipLease(
                                FIRST,
                                lease.fencingToken() + 1
                        )
                )
        );
        assertEquals(
                ProxyMembershipRenewResult.Status.CONFLICT,
                wrongFencing.status()
        );
    }

    @Test
    void expirationAllowsNewIncarnationWithHigherFencing() throws Exception {
        RedisProxyMembershipCoordinator shortFirst =
                new RedisProxyMembershipCoordinator(
                        firstConnection.async(),
                        Duration.ofMillis(250)
                );
        RedisProxyMembershipCoordinator shortSecond =
                new RedisProxyMembershipCoordinator(
                        secondConnection.async(),
                        Duration.ofMillis(250)
                );

        ProxyMembershipLease firstLease = await(
                shortFirst.acquire(FIRST)
        ).lease().orElseThrow();

        TimeUnit.MILLISECONDS.sleep(400);

        ProxyMembershipLease secondLease = await(
                shortSecond.acquire(SECOND_SAME_NAME)
        ).lease().orElseThrow();

        assertTrue(secondLease.fencingToken() > firstLease.fencingToken());
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

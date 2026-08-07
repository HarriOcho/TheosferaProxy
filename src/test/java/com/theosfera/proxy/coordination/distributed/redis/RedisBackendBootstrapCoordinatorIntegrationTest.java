package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendBootstrapAcquireRequest;
import com.theosfera.proxy.coordination.BackendBootstrapAcquireResult;
import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.BackendBootstrapReleaseResult;
import com.theosfera.proxy.coordination.BackendBootstrapRenewResult;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisBackendBootstrapCoordinatorIntegrationTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    private static final ProxyInstanceIdentity FIRST_PROXY =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString("11111111-1111-1111-1111-111111111111")
            );

    private static final ProxyInstanceIdentity SECOND_PROXY =
            new ProxyInstanceIdentity(
                    "proxy-2",
                    UUID.fromString("22222222-2222-2222-2222-222222222222")
            );

    private static final UUID PLAYER_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID FIRST_REQUEST_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID SECOND_REQUEST_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static GenericContainer<?> redis;
    private static RedisClient firstClient;
    private static RedisClient secondClient;
    private static StatefulRedisConnection<String, String> firstConnection;
    private static StatefulRedisConnection<String, String> secondConnection;

    private RedisProxyMembershipCoordinator firstMembershipCoordinator;
    private RedisProxyMembershipCoordinator secondMembershipCoordinator;
    private RedisBackendBootstrapCoordinator firstBootstrapCoordinator;
    private RedisBackendBootstrapCoordinator secondBootstrapCoordinator;

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

        firstMembershipCoordinator = new RedisProxyMembershipCoordinator(
                firstConnection.async(),
                Duration.ofSeconds(15)
        );
        secondMembershipCoordinator = new RedisProxyMembershipCoordinator(
                secondConnection.async(),
                Duration.ofSeconds(15)
        );
        firstBootstrapCoordinator = new RedisBackendBootstrapCoordinator(
                firstConnection.async(),
                Duration.ofSeconds(15)
        );
        secondBootstrapCoordinator = new RedisBackendBootstrapCoordinator(
                secondConnection.async(),
                Duration.ofSeconds(15)
        );
    }

    @Test
    void bootstrapRequiresCurrentProxyMembership() throws Exception {
        ProxyMembershipLease fabricatedMembership = new ProxyMembershipLease(
                FIRST_PROXY,
                1L
        );

        BackendBootstrapAcquireResult result = await(
                firstBootstrapCoordinator.acquire(
                        request(
                                "lobby-1",
                                FIRST_REQUEST_ID,
                                fabricatedMembership
                        )
                )
        );

        assertEquals(
                BackendBootstrapAcquireResult.Status.MEMBERSHIP_NOT_FOUND,
                result.status()
        );
        assertTrue(result.acquiredLease().isEmpty());
    }

    @Test
    void sameBackendCannotHaveTwoBootstrapOwners() throws Exception {
        ProxyMembershipLease firstMembership = acquireMembership(
                firstMembershipCoordinator,
                FIRST_PROXY
        );
        ProxyMembershipLease secondMembership = acquireMembership(
                secondMembershipCoordinator,
                SECOND_PROXY
        );

        BackendBootstrapAcquireResult first = await(
                firstBootstrapCoordinator.acquire(
                        request(
                                "lobby-1",
                                FIRST_REQUEST_ID,
                                firstMembership
                        )
                )
        );

        assertEquals(
                BackendBootstrapAcquireResult.Status.ACQUIRED,
                first.status()
        );

        BackendBootstrapAcquireResult competing = await(
                secondBootstrapCoordinator.acquire(
                        request(
                                "lobby-1",
                                SECOND_REQUEST_ID,
                                secondMembership
                        )
                )
        );

        assertEquals(
                BackendBootstrapAcquireResult.Status.TARGET_BUSY,
                competing.status()
        );
        assertTrue(competing.acquiredLease().isEmpty());
    }

    @Test
    void repeatedExactAcquireIsIdempotent() throws Exception {
        ProxyMembershipLease membership = acquireMembership(
                firstMembershipCoordinator,
                FIRST_PROXY
        );
        BackendBootstrapAcquireRequest request = request(
                "skyblock-1",
                FIRST_REQUEST_ID,
                membership
        );

        BackendBootstrapAcquireResult first = await(
                firstBootstrapCoordinator.acquire(request)
        );
        BackendBootstrapAcquireResult repeated = await(
                firstBootstrapCoordinator.acquire(request)
        );

        assertEquals(
                BackendBootstrapAcquireResult.Status.ACQUIRED,
                first.status()
        );
        assertEquals(
                BackendBootstrapAcquireResult.Status.ALREADY_OWNED,
                repeated.status()
        );
        assertEquals(
                first.acquiredLease().orElseThrow(),
                repeated.acquiredLease().orElseThrow()
        );
    }

    @Test
    void requestIdCannotOwnTwoBackendBootstraps() throws Exception {
        ProxyMembershipLease membership = acquireMembership(
                firstMembershipCoordinator,
                FIRST_PROXY
        );

        BackendBootstrapAcquireResult first = await(
                firstBootstrapCoordinator.acquire(
                        request(
                                "lobby-1",
                                FIRST_REQUEST_ID,
                                membership
                        )
                )
        );
        assertEquals(
                BackendBootstrapAcquireResult.Status.ACQUIRED,
                first.status()
        );

        BackendBootstrapAcquireResult conflicting = await(
                firstBootstrapCoordinator.acquire(
                        request(
                                "lobby-2",
                                FIRST_REQUEST_ID,
                                membership
                        )
                )
        );

        assertEquals(
                BackendBootstrapAcquireResult.Status.REQUEST_ID_CONFLICT,
                conflicting.status()
        );
    }

    @Test
    void staleMembershipCannotRenewOrReleaseBootstrapLease() throws Exception {
        ProxyMembershipLease oldMembership = acquireMembership(
                firstMembershipCoordinator,
                FIRST_PROXY
        );
        BackendBootstrapLease bootstrapLease = await(
                firstBootstrapCoordinator.acquire(
                        request(
                                "lobby-1",
                                FIRST_REQUEST_ID,
                                oldMembership
                        )
                )
        ).acquiredLease().orElseThrow();

        assertTrue(await(
                firstMembershipCoordinator.releaseIfOwned(oldMembership)
        ));

        ProxyMembershipLease currentMembership = acquireMembership(
                firstMembershipCoordinator,
                FIRST_PROXY
        );
        assertTrue(
                currentMembership.fencingToken()
                        > oldMembership.fencingToken()
        );

        BackendBootstrapRenewResult renew = await(
                firstBootstrapCoordinator.renew(bootstrapLease)
        );
        assertEquals(
                BackendBootstrapRenewResult.Status.NOT_MEMBERSHIP_OWNER,
                renew.status()
        );

        BackendBootstrapReleaseResult release = await(
                firstBootstrapCoordinator.releaseIfOwned(bootstrapLease)
        );
        assertEquals(
                BackendBootstrapReleaseResult.Status.NOT_MEMBERSHIP_OWNER,
                release.status()
        );
    }

    @Test
    void bootstrapFencingRejectsForgedLease() throws Exception {
        ProxyMembershipLease membership = acquireMembership(
                firstMembershipCoordinator,
                FIRST_PROXY
        );
        BackendBootstrapLease lease = await(
                firstBootstrapCoordinator.acquire(
                        request(
                                "lobby-1",
                                FIRST_REQUEST_ID,
                                membership
                        )
                )
        ).acquiredLease().orElseThrow();

        BackendBootstrapLease forged = new BackendBootstrapLease(
                lease.targetBackendName(),
                lease.requestId(),
                lease.playerId(),
                lease.ownerMembership(),
                lease.fencingToken() + 1L
        );

        BackendBootstrapRenewResult renew = await(
                firstBootstrapCoordinator.renew(forged)
        );
        assertEquals(
                BackendBootstrapRenewResult.Status.CONFLICT,
                renew.status()
        );

        BackendBootstrapReleaseResult release = await(
                firstBootstrapCoordinator.releaseIfOwned(forged)
        );
        assertEquals(
                BackendBootstrapReleaseResult.Status.CONFLICT,
                release.status()
        );
    }

    @Test
    void expirationAllowsNewOwnerWithHigherBootstrapFencing() throws Exception {
        ProxyMembershipLease firstMembership = acquireMembership(
                firstMembershipCoordinator,
                FIRST_PROXY
        );
        ProxyMembershipLease secondMembership = acquireMembership(
                secondMembershipCoordinator,
                SECOND_PROXY
        );

        RedisBackendBootstrapCoordinator shortFirst =
                new RedisBackendBootstrapCoordinator(
                        firstConnection.async(),
                        Duration.ofMillis(250)
                );
        RedisBackendBootstrapCoordinator shortSecond =
                new RedisBackendBootstrapCoordinator(
                        secondConnection.async(),
                        Duration.ofMillis(250)
                );

        BackendBootstrapLease firstLease = await(
                shortFirst.acquire(
                        request(
                                "lobby-1",
                                FIRST_REQUEST_ID,
                                firstMembership
                        )
                )
        ).acquiredLease().orElseThrow();

        TimeUnit.MILLISECONDS.sleep(400);

        BackendBootstrapLease secondLease = await(
                shortSecond.acquire(
                        request(
                                "lobby-1",
                                SECOND_REQUEST_ID,
                                secondMembership
                        )
                )
        ).acquiredLease().orElseThrow();

        assertTrue(secondLease.fencingToken() > firstLease.fencingToken());
    }

    private static ProxyMembershipLease acquireMembership(
            RedisProxyMembershipCoordinator coordinator,
            ProxyInstanceIdentity identity
    ) throws Exception {
        return await(coordinator.acquire(identity))
                .lease()
                .orElseThrow();
    }

    private static BackendBootstrapAcquireRequest request(
            String backendName,
            UUID requestId,
            ProxyMembershipLease membershipLease
    ) {
        return new BackendBootstrapAcquireRequest(
                backendName,
                requestId,
                PLAYER_ID,
                membershipLease
        );
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

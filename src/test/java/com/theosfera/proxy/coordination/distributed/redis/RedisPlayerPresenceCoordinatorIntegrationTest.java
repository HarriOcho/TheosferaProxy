package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.DistributedPlayerPresence;
import com.theosfera.proxy.coordination.PlayerPresencePublishRequest;
import com.theosfera.proxy.coordination.PlayerPresencePublishResult;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveRequest;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;
import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisPlayerPresenceCoordinatorIntegrationTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    private static final UUID PLAYER_ID = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );

    private static final ProxyInstanceIdentity FIRST_OWNER =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString(
                            "d505feca-365c-4fb4-818e-3efccf124d97"
                    )
            );

    private static final ProxyInstanceIdentity SECOND_OWNER =
            new ProxyInstanceIdentity(
                    "proxy-2",
                    UUID.fromString(
                            "7f48ad12-9ccd-47eb-a075-8823e337108a"
                    )
            );

    private static GenericContainer<?> redis;
    private static RedisClient firstClient;
    private static RedisClient secondClient;
    private static StatefulRedisConnection<String, String> firstConnection;
    private static StatefulRedisConnection<String, String> secondConnection;

    private RedisPlayerSessionCoordinator firstSessionCoordinator;
    private RedisPlayerSessionCoordinator secondSessionCoordinator;
    private RedisPlayerPresenceCoordinator firstPresenceCoordinator;
    private RedisPlayerPresenceCoordinator secondPresenceCoordinator;

    @BeforeAll
    static void startRedis() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            String message =
                    "Docker is not available for Redis Testcontainers";

            if (RedisTestcontainersSupport
                    .shouldFailWhenDockerUnavailable()) {
                throw new IllegalStateException(message);
            }

            Assumptions.assumeTrue(false, message);
        }

        redis = new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(6379);
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

        firstSessionCoordinator = new RedisPlayerSessionCoordinator(
                firstConnection.async(),
                new AuthenticatedPlayerSessionRegistry(),
                Duration.ofSeconds(30)
        );
        secondSessionCoordinator = new RedisPlayerSessionCoordinator(
                secondConnection.async(),
                new AuthenticatedPlayerSessionRegistry(),
                Duration.ofSeconds(30)
        );
        firstPresenceCoordinator = new RedisPlayerPresenceCoordinator(
                firstConnection.async(),
                Duration.ofSeconds(30)
        );
        secondPresenceCoordinator = new RedisPlayerPresenceCoordinator(
                secondConnection.async(),
                Duration.ofSeconds(30)
        );
    }

    @Test
    void publishIsFencedOrderedAndIdempotent() throws Exception {
        PlayerSessionLease lease = acquire(
                firstSessionCoordinator,
                FIRST_OWNER
        );

        PlayerPresencePublishResult recorded = await(
                firstPresenceCoordinator.publish(
                        publish(lease, "lobby-1", 1L, 1_000L)
                )
        );
        assertEquals(
                PlayerPresencePublishResult.Status.RECORDED,
                recorded.status()
        );

        DistributedPlayerPresence stored = await(
                firstPresenceCoordinator.find(PLAYER_ID)
        ).orElseThrow();
        assertEquals("lobby-1", stored.backendName());
        assertEquals(lease.fencingToken(), stored.sessionFencingToken());
        assertEquals(1L, stored.sequence());

        PlayerPresencePublishResult repeated = await(
                firstPresenceCoordinator.publish(
                        publish(lease, "lobby-1", 1L, 1_000L)
                )
        );
        assertEquals(
                PlayerPresencePublishResult.Status.ALREADY_RECORDED,
                repeated.status()
        );

        PlayerPresencePublishResult updated = await(
                firstPresenceCoordinator.publish(
                        publish(lease, "skyblock-1", 2L, 2_000L)
                )
        );
        assertEquals(
                PlayerPresencePublishResult.Status.UPDATED,
                updated.status()
        );

        PlayerPresencePublishResult stale = await(
                firstPresenceCoordinator.publish(
                        publish(lease, "lobby-1", 1L, 3_000L)
                )
        );
        assertEquals(
                PlayerPresencePublishResult.Status.STALE,
                stale.status()
        );

        PlayerPresencePublishResult conflict = await(
                firstPresenceCoordinator.publish(
                        publish(lease, "lobby-1", 2L, 2_500L)
                )
        );
        assertEquals(
                PlayerPresencePublishResult.Status.CONFLICT,
                conflict.status()
        );
    }

    @Test
    void currentSessionLeaseIsRequiredForEveryMutation()
            throws Exception {
        PlayerSessionLease firstLease = acquire(
                firstSessionCoordinator,
                FIRST_OWNER
        );

        assertEquals(
                PlayerPresencePublishResult.Status.RECORDED,
                await(firstPresenceCoordinator.publish(
                        publish(firstLease, "lobby-1", 1L, 1_000L)
                )).status()
        );

        assertTrue(await(firstSessionCoordinator.releaseIfOwned(firstLease)));

        PlayerSessionLease secondLease = acquire(
                secondSessionCoordinator,
                SECOND_OWNER
        );
        assertTrue(secondLease.fencingToken() > firstLease.fencingToken());

        assertEquals(
                PlayerPresencePublishResult.Status.NOT_SESSION_OWNER,
                await(firstPresenceCoordinator.publish(
                        publish(firstLease, "lobby-2", 2L, 2_000L)
                )).status()
        );

        assertEquals(
                PlayerPresenceRemoveResult.Status.NOT_SESSION_OWNER,
                await(firstPresenceCoordinator.removeIfOwned(
                        remove(firstLease, "lobby-1", 1L)
                )).status()
        );

        assertEquals(
                PlayerPresencePublishResult.Status.UPDATED,
                await(secondPresenceCoordinator.publish(
                        publish(secondLease, "lobby-2", 1L, 3_000L)
                )).status()
        );

        DistributedPlayerPresence current = await(
                secondPresenceCoordinator.find(PLAYER_ID)
        ).orElseThrow();
        assertEquals("lobby-2", current.backendName());
        assertEquals(secondLease.fencingToken(), current.sessionFencingToken());
    }

    @Test
    void exactRemoveRejectsLateOrConflictingCallbacks() throws Exception {
        PlayerSessionLease lease = acquire(
                firstSessionCoordinator,
                FIRST_OWNER
        );

        await(firstPresenceCoordinator.publish(
                publish(lease, "skyblock-1", 2L, 2_000L)
        ));

        assertEquals(
                PlayerPresenceRemoveResult.Status.STALE,
                await(firstPresenceCoordinator.removeIfOwned(
                        remove(lease, "lobby-1", 1L)
                )).status()
        );

        assertEquals(
                PlayerPresenceRemoveResult.Status.CONFLICT,
                await(firstPresenceCoordinator.removeIfOwned(
                        remove(lease, "lobby-1", 2L)
                )).status()
        );

        assertEquals(
                PlayerPresenceRemoveResult.Status.REMOVED,
                await(firstPresenceCoordinator.removeIfOwned(
                        remove(lease, "skyblock-1", 2L)
                )).status()
        );
        assertTrue(await(firstPresenceCoordinator.find(PLAYER_ID)).isEmpty());

        assertEquals(
                PlayerPresenceRemoveResult.Status.NOT_FOUND,
                await(firstPresenceCoordinator.removeIfOwned(
                        remove(lease, "skyblock-1", 2L)
                )).status()
        );
    }

    @Test
    void publishWithoutSessionFailsClosed() throws Exception {
        PlayerSessionLease inventedLease = new PlayerSessionLease(
                session(),
                FIRST_OWNER,
                99L
        );

        PlayerPresencePublishResult result = await(
                firstPresenceCoordinator.publish(
                        publish(inventedLease, "lobby-1", 1L, 1_000L)
                )
        );

        assertEquals(
                PlayerPresencePublishResult.Status.SESSION_NOT_FOUND,
                result.status()
        );
        assertTrue(await(firstPresenceCoordinator.find(PLAYER_ID)).isEmpty());
    }

    @Test
    void corruptPresenceStateFailsClosed() throws Exception {
        PlayerSessionLease lease = acquire(
                firstSessionCoordinator,
                FIRST_OWNER
        );

        RedisPlayerPresenceKeyspace keyspace =
                RedisPlayerPresenceKeyspace.defaultKeyspace();
        await(firstConnection.async().set(
                keyspace.playerPresenceKey(PLAYER_ID),
                "corrupt"
        ));
        await(firstConnection.async().pexpire(
                keyspace.playerPresenceKey(PLAYER_ID),
                30_000L
        ));

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> firstPresenceCoordinator.publish(
                        publish(lease, "lobby-1", 1L, 1_000L)
                ).toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        assertTrue(
                failure.getCause()
                        instanceof RedisPlayerPresenceInvalidStateException
        );
    }

    private PlayerSessionLease acquire(
            RedisPlayerSessionCoordinator coordinator,
            ProxyInstanceIdentity owner
    ) throws Exception {
        PlayerSessionAcquireResult result = await(coordinator.acquire(
                new PlayerSessionLeaseRequest(session(), owner)
        ));
        assertTrue(
                result.status() == PlayerSessionAcquireResult.Status.ACQUIRED
                        || result.status()
                        == PlayerSessionAcquireResult.Status.ALREADY_OWNED
        );
        return result.lease().orElseThrow();
    }

    private PlayerPresencePublishRequest publish(
            PlayerSessionLease lease,
            String backend,
            long sequence,
            long observedAt
    ) {
        return new PlayerPresencePublishRequest(
                lease,
                backend,
                sequence,
                observedAt
        );
    }

    private PlayerPresenceRemoveRequest remove(
            PlayerSessionLease lease,
            String backend,
            long sequence
    ) {
        return new PlayerPresenceRemoveRequest(
                lease,
                backend,
                sequence
        );
    }

    private AuthenticatedPlayerSession session() {
        return new AuthenticatedPlayerSession(
                PLAYER_ID,
                "HarriOcho",
                500L
        );
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}

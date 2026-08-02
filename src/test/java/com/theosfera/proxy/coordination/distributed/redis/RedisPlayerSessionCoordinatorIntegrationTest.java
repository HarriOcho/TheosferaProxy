package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.PlayerSessionRenewResult;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisPlayerSessionCoordinatorIntegrationTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final ProxyInstanceIdentity OWNER =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString(
                            "d505feca-365c-4fb4-818e-3efccf124d97"
                    )
            );

    private static final ProxyInstanceIdentity SAME_NAME_NEW_PROCESS =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString(
                            "aaaaaaaa-1111-2222-3333-444444444444"
                    )
            );

    private static final ProxyInstanceIdentity OTHER_OWNER =
            new ProxyInstanceIdentity(
                    "proxy-2",
                    UUID.fromString(
                            "7f48ad12-9ccd-47eb-a075-8823e337108a"
                    )
            );

    private static GenericContainer<?> redis;
    private static RedisClient firstClient;
    private static RedisClient secondClient;
    private static StatefulRedisConnection<String, String>
            firstConnection;
    private static StatefulRedisConnection<String, String>
            secondConnection;

    private RedisPlayerSessionCoordinator firstCoordinator;
    private RedisPlayerSessionCoordinator secondCoordinator;
    private AuthenticatedPlayerSessionRegistry firstRegistry;
    private AuthenticatedPlayerSessionRegistry secondRegistry;

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

        String redisUri =
                "redis://"
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

        firstRegistry =
                new AuthenticatedPlayerSessionRegistry();
        secondRegistry =
                new AuthenticatedPlayerSessionRegistry();

        firstCoordinator =
                coordinator(
                        firstConnection,
                        firstRegistry,
                        Duration.ofSeconds(30)
                );
        secondCoordinator =
                coordinator(
                        secondConnection,
                        secondRegistry,
                        Duration.ofSeconds(30)
                );
    }

    @Test
    void acquireIsAtomicIdempotentAndRejectsConflicts()
            throws Exception {
        PlayerSessionAcquireResult acquired =
                await(firstCoordinator.acquire(
                        request(session(), OWNER)
                ));

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                acquired.status()
        );

        PlayerSessionLease lease =
                acquired.lease().orElseThrow();

        assertTrue(lease.fencingToken() > 0);

        PlayerSessionAcquireResult repeated =
                await(firstCoordinator.acquire(
                        request(session(), OWNER)
                ));

        assertEquals(
                PlayerSessionAcquireResult.Status.ALREADY_OWNED,
                repeated.status()
        );
        assertEquals(
                lease.fencingToken(),
                repeated.lease().orElseThrow().fencingToken()
        );

        PlayerSessionAcquireResult sameNameNewProcess =
                await(secondCoordinator.acquire(
                        request(session(), SAME_NAME_NEW_PROCESS)
                ));

        assertEquals(
                PlayerSessionAcquireResult.Status
                        .OWNED_BY_OTHER_PROXY,
                sameNameNewProcess.status()
        );

        PlayerSessionAcquireResult otherOwner =
                await(secondCoordinator.acquire(
                        request(session(), OTHER_OWNER)
                ));

        assertEquals(
                PlayerSessionAcquireResult.Status
                        .OWNED_BY_OTHER_PROXY,
                otherOwner.status()
        );

        PlayerSessionAcquireResult conflict =
                await(firstCoordinator.acquire(
                        request(conflictingSession(), OWNER)
                ));

        assertEquals(
                PlayerSessionAcquireResult.Status.CONFLICT,
                conflict.status()
        );
    }

    @Test
    void renewIsExactAndExtendsTtl()
            throws Exception {
        PlayerSessionLease lease =
                await(firstCoordinator.acquire(
                        request(session(), OWNER)
                )).lease().orElseThrow();

        Long ttlBefore =
                await(firstConnection.async().pttl(
                        sessionKey()
                ));

        await(waitForTtlBelow(ttlBefore));

        PlayerSessionRenewResult renewed =
                await(firstCoordinator.renew(lease));

        assertEquals(
                PlayerSessionRenewResult.Status.RENEWED,
                renewed.status()
        );
        assertEquals(
                lease.fencingToken(),
                renewed.lease().orElseThrow().fencingToken()
        );

        Long ttlAfter =
                await(firstConnection.async().pttl(
                        sessionKey()
                ));

        assertTrue(ttlAfter > ttlBefore - 1_000L);

        assertTrue(await(firstCoordinator.releaseIfOwned(lease)));

        assertEquals(
                PlayerSessionRenewResult.Status.NOT_FOUND,
                await(firstCoordinator.renew(lease)).status()
        );
    }

    @Test
    void renewDistinguishesAbsentOtherOwnerAndStale()
            throws Exception {
        PlayerSessionLease absent =
                new PlayerSessionLease(
                        session(),
                        OWNER,
                        1L
                );

        assertEquals(
                PlayerSessionRenewResult.Status.NOT_FOUND,
                await(firstCoordinator.renew(absent)).status()
        );

        PlayerSessionLease lease =
                await(firstCoordinator.acquire(
                        request(session(), OWNER)
                )).lease().orElseThrow();

        PlayerSessionLease otherOwner =
                new PlayerSessionLease(
                        lease.session(),
                        OTHER_OWNER,
                        lease.fencingToken()
                );

        assertEquals(
                PlayerSessionRenewResult.Status.NOT_OWNER,
                await(secondCoordinator.renew(otherOwner))
                        .status()
        );

        PlayerSessionLease stale =
                new PlayerSessionLease(
                        lease.session(),
                        lease.owner(),
                        lease.fencingToken() + 1L
                );

        assertEquals(
                PlayerSessionRenewResult.Status.CONFLICT,
                await(firstCoordinator.renew(stale)).status()
        );
    }

    @Test
    void releaseIsExactAndStaleReleaseCannotDeleteNewLease()
            throws Exception {
        PlayerSessionLease first =
                await(firstCoordinator.acquire(
                        request(session(), OWNER)
                )).lease().orElseThrow();

        assertTrue(
                await(firstCoordinator.releaseIfOwned(first))
        );
        assertFalse(
                await(firstCoordinator.releaseIfOwned(first))
        );

        PlayerSessionLease second =
                await(firstCoordinator.acquire(
                        request(session(), OWNER)
                )).lease().orElseThrow();

        assertTrue(
                second.fencingToken() > first.fencingToken()
        );

        assertFalse(
                await(firstCoordinator.releaseIfOwned(first))
        );

        assertEquals(
                PlayerSessionRenewResult.Status.RENEWED,
                await(firstCoordinator.renew(second)).status()
        );
    }

    @Test
    void reacquireAfterExpiryGetsHigherFencingAndTtl()
            throws Exception {
        RedisPlayerSessionCoordinator shortTtlCoordinator =
                coordinator(
                        firstConnection,
                        firstRegistry,
                        Duration.ofMillis(300)
                );

        PlayerSessionLease first =
                await(shortTtlCoordinator.acquire(
                        request(session(), OWNER)
                )).lease().orElseThrow();

        assertTrue(
                await(firstConnection.async().pttl(
                        sessionKey()
                )) > 0
        );

        await(waitUntilMissing());

        PlayerSessionLease second =
                await(secondCoordinator.acquire(
                        request(session(), OTHER_OWNER)
                )).lease().orElseThrow();

        assertTrue(
                second.fencingToken() > first.fencingToken()
        );
    }

    @Test
    void concurrentDifferentOwnersCannotBothAcquire()
            throws Exception {
        CompletableFuture<PlayerSessionAcquireResult> first =
                firstCoordinator.acquire(
                        request(session(), OWNER)
                ).toCompletableFuture();
        CompletableFuture<PlayerSessionAcquireResult> second =
                secondCoordinator.acquire(
                        request(session(), OTHER_OWNER)
                ).toCompletableFuture();

        List<PlayerSessionAcquireResult> results =
                List.of(
                        await(first),
                        await(second)
                );

        long acquiredCount =
                results.stream()
                        .filter(result -> result.status()
                                == PlayerSessionAcquireResult
                                .Status.ACQUIRED)
                        .count();

        long rejectedCount =
                results.stream()
                        .filter(result -> result.status()
                                == PlayerSessionAcquireResult
                                .Status.OWNED_BY_OTHER_PROXY)
                        .count();

        assertEquals(1L, acquiredCount);
        assertEquals(1L, rejectedCount);
    }

    @Test
    void concurrentSameOwnerSameSessionHasOneAuthority()
            throws Exception {
        CompletableFuture<PlayerSessionAcquireResult> first =
                firstCoordinator.acquire(
                        request(session(), OWNER)
                ).toCompletableFuture();
        CompletableFuture<PlayerSessionAcquireResult> second =
                secondCoordinator.acquire(
                        request(session(), OWNER)
                ).toCompletableFuture();

        PlayerSessionAcquireResult firstResult =
                await(first);
        PlayerSessionAcquireResult secondResult =
                await(second);

        assertTrue(
                firstResult.lease().isPresent()
                        || secondResult.lease().isPresent()
        );

        long firstToken =
                firstResult.lease()
                        .orElseGet(
                                () -> secondResult.lease()
                                        .orElseThrow()
                        ).fencingToken();
        long secondToken =
                secondResult.lease()
                        .orElseGet(
                                () -> firstResult.lease()
                                        .orElseThrow()
                        ).fencingToken();

        assertEquals(firstToken, secondToken);
        assertTrue(
                firstResult.status()
                        == PlayerSessionAcquireResult.Status.ACQUIRED
                        || secondResult.status()
                        == PlayerSessionAcquireResult.Status.ACQUIRED
        );
    }

    @Test
    void corruptHashesAndWrongTypesFailClosedWithoutMutation()
            throws Exception {
        await(firstConnection.async().hset(
                sessionKey(),
                "proxy-name",
                OWNER.proxyName()
        ));

        assertAcquireFailsExceptionally();
        assertRenewFailsExceptionally(lease(1L));
        assertReleaseFailsExceptionally(lease(1L));

        assertEquals(
                Map.of("proxy-name", OWNER.proxyName()),
                await(firstConnection.async().hgetall(sessionKey()))
        );

        await(firstConnection.async().flushall());
        await(firstConnection.async().set(sessionKey(), "not-a-hash"));

        assertAcquireFailsExceptionally();
        assertRenewFailsExceptionally(lease(1L));
        assertReleaseFailsExceptionally(lease(1L));
        assertEquals(
                "string",
                await(firstConnection.async().type(sessionKey()))
        );

        await(firstConnection.async().flushall());
        await(writeFullHash(OWNER, session(), "not-a-number"));
        await(firstConnection.async().pexpire(
                sessionKey(),
                Duration.ofSeconds(30).toMillis()
        ));

        assertAcquireFailsExceptionally();
        assertRenewFailsExceptionally(lease(1L));
        assertReleaseFailsExceptionally(lease(1L));
        assertEquals(
                "not-a-number",
                await(firstConnection.async().hget(
                        sessionKey(),
                        "fencing-token"
                ))
        );

        await(firstConnection.async().flushall());
        await(writeFullHash(OWNER, session(), "1"));

        assertAcquireFailsExceptionally();
        assertRenewFailsExceptionally(lease(1L));
        assertReleaseFailsExceptionally(lease(1L));
        assertEquals(
                -1L,
                await(firstConnection.async().pttl(sessionKey()))
        );
    }

    @Test
    void corruptFencingCounterFailsClosedWithoutCreatingLease()
            throws Exception {
        await(firstConnection.async().set(
                RedisPlayerSessionKeyspace.defaultKeyspace()
                        .fencingCounterKey(),
                "not-a-number"
        ));

        assertAcquireFailsExceptionally();

        assertEquals(
                0L,
                await(firstConnection.async().exists(sessionKey()))
        );
        assertEquals(
                "not-a-number",
                await(firstConnection.async().get(
                        RedisPlayerSessionKeyspace.defaultKeyspace()
                                .fencingCounterKey()
                ))
        );
    }

    @Test
    void cleanupDoesNotRemoveLaterFencing()
            throws Exception {
        RedisPlayerSessionCoordinator conflictingLocal =
                coordinator(
                        firstConnection,
                        firstRegistry,
                        Duration.ofSeconds(30)
                );

        firstRegistry.register(conflictingSession());

        PlayerSessionAcquireResult result =
                await(conflictingLocal.acquire(
                        request(session(), OWNER)
                ));

        assertEquals(
                PlayerSessionAcquireResult.Status.CONFLICT,
                result.status()
        );

        PlayerSessionLease current =
                await(secondCoordinator.acquire(
                        request(session(), OTHER_OWNER)
                )).lease().orElseThrow();

        PlayerSessionLease stale =
                new PlayerSessionLease(
                        session(),
                        OWNER,
                        Math.max(1L, current.fencingToken() - 1L)
                );

        assertFalse(
                await(firstCoordinator.releaseIfOwned(stale))
        );

        assertEquals(
                PlayerSessionRenewResult.Status.RENEWED,
                await(secondCoordinator.renew(current)).status()
        );
    }

    private RedisPlayerSessionCoordinator coordinator(
            StatefulRedisConnection<String, String> connection,
            AuthenticatedPlayerSessionRegistry registry,
            Duration ttl
    ) {
        return new RedisPlayerSessionCoordinator(
                connection.async(),
                registry,
                ttl,
                RedisPlayerSessionKeyspace.defaultKeyspace()
        );
    }

    private PlayerSessionLeaseRequest request(
            AuthenticatedPlayerSession session,
            ProxyInstanceIdentity owner
    ) {
        return new PlayerSessionLeaseRequest(
                session,
                owner
        );
    }

    private AuthenticatedPlayerSession session() {
        return new AuthenticatedPlayerSession(
                PLAYER_ID,
                "HarriOcho",
                1_750_000_000_000L
        );
    }

    private AuthenticatedPlayerSession conflictingSession() {
        return new AuthenticatedPlayerSession(
                PLAYER_ID,
                "HarriOcho",
                1_750_000_000_025L
        );
    }

    private PlayerSessionLease lease(long fencingToken) {
        return new PlayerSessionLease(
                session(),
                OWNER,
                fencingToken
        );
    }

    private String sessionKey() {
        return RedisPlayerSessionKeyspace
                .defaultKeyspace()
                .playerSessionKey(PLAYER_ID);
    }

    private CompletableFuture<Boolean> waitForTtlBelow(
            long previousTtl
    ) {
        return poll(
                () -> firstConnection.async()
                        .pttl(sessionKey())
                        .thenApply(ttl -> ttl < previousTtl)
        );
    }

    private CompletableFuture<Boolean> waitUntilMissing() {
        return poll(
                () -> firstConnection.async()
                        .exists(sessionKey())
                        .thenApply(count -> count == 0L)
        );
    }

    private java.util.concurrent.CompletionStage<Boolean> writeFullHash(
            ProxyInstanceIdentity owner,
            AuthenticatedPlayerSession session,
            String fencingToken
    ) {
        Map<String, String> fields = Map.of(
                "player-id",
                session.playerId().toString(),
                "player-name",
                session.playerName(),
                "authenticated-at",
                Long.toString(session.authenticatedAt()),
                "proxy-name",
                owner.proxyName(),
                "incarnation-id",
                owner.incarnationId().toString(),
                "fencing-token",
                fencingToken
        );

        return firstConnection.async()
                .hmset(sessionKey(), fields)
                .thenApply("OK"::equals);
    }

    private void assertAcquireFailsExceptionally() {
        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> firstCoordinator.acquire(
                        request(session(), OWNER)
                ).toCompletableFuture().get(5, TimeUnit.SECONDS)
        );

        assertTrue(exception.getCause() instanceof RuntimeException);
    }

    private void assertRenewFailsExceptionally(
            PlayerSessionLease lease
    ) {
        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> firstCoordinator.renew(lease)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
        );

        assertTrue(exception.getCause() instanceof RuntimeException);
    }

    private void assertReleaseFailsExceptionally(
            PlayerSessionLease lease
    ) {
        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> firstCoordinator.releaseIfOwned(lease)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
        );

        assertTrue(exception.getCause() instanceof RuntimeException);
    }

    private CompletableFuture<Boolean> poll(
            java.util.function.Supplier<
                    java.util.concurrent.CompletionStage<Boolean>>
                    condition
    ) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        CompletableFuture<Boolean> result =
                new CompletableFuture<>();
        poll(condition, deadline, result);
        return result;
    }

    private void poll(
            java.util.function.Supplier<
                    java.util.concurrent.CompletionStage<Boolean>>
                    condition,
            long deadline,
            CompletableFuture<Boolean> result
    ) {
        condition.get().whenComplete(
                (matched, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }

                    if (Boolean.TRUE.equals(matched)) {
                        result.complete(true);
                        return;
                    }

                    if (System.nanoTime() > deadline) {
                        result.complete(false);
                        return;
                    }

                    CompletableFuture.delayedExecutor(
                            25,
                            TimeUnit.MILLISECONDS
                    ).execute(
                            () -> poll(
                                    condition,
                                    deadline,
                                    result
                            )
                    );
                }
        );
    }

    private static <T> T await(
            java.util.concurrent.CompletionStage<T> stage
    ) throws Exception {
        return stage.toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }
}

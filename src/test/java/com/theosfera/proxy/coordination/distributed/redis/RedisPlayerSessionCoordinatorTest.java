package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.PlayerSessionRenewResult;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisPlayerSessionCoordinatorTest {

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

    @Test
    void rejectsInvalidConstructorArguments() {
        RedisPlayerSessionStore store =
                new StubStore();
        AuthenticatedPlayerSessionRegistry registry =
                new AuthenticatedPlayerSessionRegistry();

        assertThrows(
                NullPointerException.class,
                () -> new RedisPlayerSessionCoordinator(
                        (RedisPlayerSessionStore) null,
                        registry,
                        Duration.ofSeconds(30)
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new RedisPlayerSessionCoordinator(
                        store,
                        null,
                        Duration.ofSeconds(30)
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new RedisPlayerSessionCoordinator(
                        store,
                        registry,
                        null
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisPlayerSessionCoordinator(
                        store,
                        registry,
                        Duration.ZERO
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisPlayerSessionCoordinator(
                        store,
                        registry,
                        Duration.ofMillis(-1)
                )
        );
    }

    @Test
    void rejectsNullOperations() {
        RedisPlayerSessionCoordinator coordinator =
                coordinator(new StubStore());

        assertThrows(
                NullPointerException.class,
                () -> coordinator.acquire(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> coordinator.renew(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> coordinator.releaseIfOwned(null)
        );
    }

    @Test
    void mapsRedisUnavailableOnAcquire()
            throws Exception {
        RedisPlayerSessionCoordinator coordinator =
                coordinator(
                        new FailingStore(
                                new RedisConnectionException(
                                        "redis unavailable"
                                )
                        )
                );

        PlayerSessionAcquireResult result =
                await(coordinator.acquire(request()));

        assertEquals(
                PlayerSessionAcquireResult.Status
                        .COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void mapsRedisUnavailableOnRenew()
            throws Exception {
        RedisPlayerSessionCoordinator coordinator =
                coordinator(
                        new FailingStore(
                                new RedisConnectionException(
                                        "redis unavailable"
                                )
                        )
                );

        PlayerSessionRenewResult result =
                await(coordinator.renew(lease(1L)));

        assertEquals(
                PlayerSessionRenewResult.Status
                        .COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void redisUnavailableOnReleaseCompletesExceptionally() {
        RedisPlayerSessionCoordinator coordinator =
                coordinator(
                        new FailingStore(
                                new RedisConnectionException(
                                        "redis unavailable"
                                )
                        )
                );

        ExecutionException exception =
                assertThrows(
                        ExecutionException.class,
                        () -> coordinator.releaseIfOwned(lease(1L))
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS)
                );

        assertTrue(
                exception.getCause()
                        instanceof RedisConnectionException
        );
    }

    @Test
    void malformedAcquireResponseCompletesExceptionally() {
        RedisPlayerSessionCoordinator coordinator =
                coordinator(new MalformedStore());

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> coordinator.acquire(request())
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
        );

        assertInstanceOf(
                IllegalStateException.class,
                exception.getCause()
        );
    }

    @Test
    void internalAcquireFailureCompletesExceptionally() {
        RedisPlayerSessionCoordinator coordinator =
                coordinator(
                        new FailingStore(
                                new RedisPlayerSessionInvalidStateException(
                                        "bad redis state"
                                )
                        )
                );

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> coordinator.acquire(request())
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
        );

        assertInstanceOf(
                RedisPlayerSessionInvalidStateException.class,
                exception.getCause()
        );
    }

    @Test
    void malformedRenewResponseCompletesExceptionally() {
        RedisPlayerSessionCoordinator coordinator =
                coordinator(new MalformedStore());

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> coordinator.renew(lease(1L))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
        );

        assertInstanceOf(
                IllegalStateException.class,
                exception.getCause()
        );
    }

    @Test
    void releaseInternalFailureCompletesExceptionally() {
        RedisPlayerSessionCoordinator coordinator =
                coordinator(
                        new FailingStore(
                                new RedisPlayerSessionInvalidStateException(
                                        "bad redis state"
                                )
                        )
                );

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> coordinator.releaseIfOwned(lease(1L))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
        );

        assertInstanceOf(
                RedisPlayerSessionInvalidStateException.class,
                exception.getCause()
        );
    }

    @Test
    void staleReleaseCallbackDoesNotRemoveNewLocalLease()
            throws Exception {
        ControllableStore store = new ControllableStore();
        AuthenticatedPlayerSessionRegistry registry =
                new AuthenticatedPlayerSessionRegistry();
        RedisPlayerSessionCoordinator coordinator =
                new RedisPlayerSessionCoordinator(
                        store,
                        registry,
                        Duration.ofSeconds(30)
                );

        PlayerSessionLease first = lease(1L);
        PlayerSessionLease second = lease(2L);

        store.enqueueAcquire(
                RedisPlayerSessionAcquireResponse.acquired(first)
        );

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                await(coordinator.acquire(request())).status()
        );
        assertEquals(
                Optional.of(first),
                coordinator.localLeaseFor(PLAYER_ID)
        );

        CompletableFuture<Boolean> release =
                coordinator.releaseIfOwned(first)
                        .toCompletableFuture();

        assertFalse(release.isDone());

        store.enqueueAcquire(
                RedisPlayerSessionAcquireResponse.acquired(second)
        );

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                await(coordinator.acquire(request())).status()
        );
        assertEquals(
                Optional.of(second),
                coordinator.localLeaseFor(PLAYER_ID)
        );
        assertEquals(
                Optional.of(session()),
                registry.find(PLAYER_ID)
        );

        store.completeRelease(true);

        assertTrue(await(release));
        assertEquals(
                Optional.of(second),
                coordinator.localLeaseFor(PLAYER_ID)
        );
        assertEquals(
                Optional.of(session()),
                registry.find(PLAYER_ID)
        );
    }

    @Test
    void acquiredGlobalLeaseWithLocalConflictAndCleanupTrueReturnsConflict()
            throws Exception {
        RedisPlayerSessionCoordinator coordinator =
                cleanupConflictCoordinator(
                        CompletableFuture.completedFuture(true)
                );

        PlayerSessionAcquireResult result =
                await(coordinator.acquire(request()));

        assertEquals(
                PlayerSessionAcquireResult.Status.CONFLICT,
                result.status()
        );
    }

    @Test
    void acquiredGlobalLeaseWithLocalConflictAndCleanupFalseReturnsUnavailable()
            throws Exception {
        RedisPlayerSessionCoordinator coordinator =
                cleanupConflictCoordinator(
                        CompletableFuture.completedFuture(false)
                );

        PlayerSessionAcquireResult result =
                await(coordinator.acquire(request()));

        assertEquals(
                PlayerSessionAcquireResult.Status
                        .COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void acquiredGlobalLeaseWithLocalConflictAndCleanupConnectionFailureReturnsUnavailable()
            throws Exception {
        RedisPlayerSessionCoordinator coordinator =
                cleanupConflictCoordinator(
                        CompletableFuture.failedFuture(
                                new RedisConnectionException(
                                        "redis unavailable"
                                )
                        )
                );

        PlayerSessionAcquireResult result =
                await(coordinator.acquire(request()));

        assertEquals(
                PlayerSessionAcquireResult.Status
                        .COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void acquiredGlobalLeaseWithLocalConflictAndCleanupTimeoutReturnsUnavailable()
            throws Exception {
        RedisPlayerSessionCoordinator coordinator =
                cleanupConflictCoordinator(
                        CompletableFuture.failedFuture(
                                new RedisCommandTimeoutException(
                                        "redis timeout"
                                )
                        )
                );

        PlayerSessionAcquireResult result =
                await(coordinator.acquire(request()));

        assertEquals(
                PlayerSessionAcquireResult.Status
                        .COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void acquiredGlobalLeaseWithLocalConflictAndCleanupInternalFailureCompletesExceptionally() {
        RedisPlayerSessionCoordinator coordinator =
                cleanupConflictCoordinator(
                        CompletableFuture.failedFuture(
                                new RedisPlayerSessionInvalidStateException(
                                        "bad redis state"
                                )
                        )
                );

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> coordinator.acquire(request())
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
        );

        assertInstanceOf(
                RedisPlayerSessionInvalidStateException.class,
                exception.getCause()
        );
    }

    @Test
    void dockerUnavailableIsOnlySkippableOutsideCi() {
        assertTrue(
                RedisTestcontainersSupport.ciRequiresDocker(
                        Map.of("CI", "true")
                )
        );
        assertFalse(
                RedisTestcontainersSupport.ciRequiresDocker(Map.of())
        );
    }

    @Test
    void doesNotUseBlockingCallsInProductionSources()
            throws Exception {
        Path root = Path.of(
                "src",
                "main",
                "java",
                "com",
                "theosfera",
                "proxy",
                "coordination",
                "distributed",
                "redis"
        );

        try (var files = Files.walk(root)) {
            String source =
                    files.filter(Files::isRegularFile)
                            .map(path -> {
                                try {
                                    return Files.readString(
                                            path,
                                            StandardCharsets.UTF_8
                                    );
                                } catch (Exception exception) {
                                    throw new IllegalStateException(
                                            exception
                                    );
                                }
                            })
                            .reduce("", String::concat);

            assertTrue(
                    !source.contains(".join(")
                            && !source.contains(".get(")
                            && !source.contains(".await(")
                            && !source.contains(".sync("),
                    "Redis production paths must remain non-blocking"
            );
        }
    }

    private RedisPlayerSessionCoordinator coordinator(
            RedisPlayerSessionStore store
    ) {
        return new RedisPlayerSessionCoordinator(
                store,
                new AuthenticatedPlayerSessionRegistry(),
                Duration.ofSeconds(30)
        );
    }

    private RedisPlayerSessionCoordinator cleanupConflictCoordinator(
            CompletionStage<Boolean> cleanupResult
    ) {
        AuthenticatedPlayerSessionRegistry registry =
                new AuthenticatedPlayerSessionRegistry();
        registry.register(conflictingSession());

        return new RedisPlayerSessionCoordinator(
                new CleanupConflictStore(
                        lease(1L),
                        cleanupResult
                ),
                registry,
                Duration.ofSeconds(30)
        );
    }

    private PlayerSessionLeaseRequest request() {
        return new PlayerSessionLeaseRequest(
                session(),
                OWNER
        );
    }

    private PlayerSessionLease lease(long fencingToken) {
        return new PlayerSessionLease(
                session(),
                OWNER,
                fencingToken
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

    private static <T> T await(
            CompletionStage<T> stage
    ) throws Exception {
        return stage.toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    private static final class StubStore
            implements RedisPlayerSessionStore {

        @Override
        public CompletionStage<RedisPlayerSessionAcquireResponse>
        acquire(
                PlayerSessionLeaseRequest request,
                Duration ttl
        ) {
            return CompletableFuture.completedFuture(
                    RedisPlayerSessionAcquireResponse
                            .withoutLease(
                                    RedisPlayerSessionAcquireStatus
                                            .OWNED_BY_OTHER_PROXY
                            )
            );
        }

        @Override
        public CompletionStage<RedisPlayerSessionRenewResponse>
        renew(
                PlayerSessionLease expected,
                Duration ttl
        ) {
            return CompletableFuture.completedFuture(
                    RedisPlayerSessionRenewResponse
                            .withoutLease(
                                    RedisPlayerSessionRenewStatus
                                            .NOT_FOUND
                            )
            );
        }

        @Override
        public CompletionStage<Boolean> releaseIfOwned(
                PlayerSessionLease expected
        ) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static final class FailingStore
            implements RedisPlayerSessionStore {

        private final RuntimeException failure;

        private FailingStore(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public CompletionStage<RedisPlayerSessionAcquireResponse>
        acquire(
                PlayerSessionLeaseRequest request,
                Duration ttl
        ) {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletionStage<RedisPlayerSessionRenewResponse>
        renew(
                PlayerSessionLease expected,
                Duration ttl
        ) {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletionStage<Boolean> releaseIfOwned(
                PlayerSessionLease expected
        ) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static final class MalformedStore
            implements RedisPlayerSessionStore {

        @Override
        public CompletionStage<RedisPlayerSessionAcquireResponse>
        acquire(
                PlayerSessionLeaseRequest request,
                Duration ttl
        ) {
            return CompletableFuture.completedFuture(
                    new RedisPlayerSessionAcquireResponse(
                            RedisPlayerSessionAcquireStatus.ACQUIRED,
                            Optional.empty()
                    )
            );
        }

        @Override
        public CompletionStage<RedisPlayerSessionRenewResponse>
        renew(
                PlayerSessionLease expected,
                Duration ttl
        ) {
            return CompletableFuture.completedFuture(
                    new RedisPlayerSessionRenewResponse(
                            RedisPlayerSessionRenewStatus.RENEWED,
                            Optional.empty()
                    )
            );
        }

        @Override
        public CompletionStage<Boolean> releaseIfOwned(
                PlayerSessionLease expected
        ) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static final class ControllableStore
            implements RedisPlayerSessionStore {

        private final Queue<RedisPlayerSessionAcquireResponse>
                acquireResponses = new ArrayDeque<>();
        private CompletableFuture<Boolean> release =
                new CompletableFuture<>();

        void enqueueAcquire(
                RedisPlayerSessionAcquireResponse response
        ) {
            acquireResponses.add(response);
        }

        void completeRelease(boolean released) {
            release.complete(released);
        }

        @Override
        public CompletionStage<RedisPlayerSessionAcquireResponse>
        acquire(
                PlayerSessionLeaseRequest request,
                Duration ttl
        ) {
            RedisPlayerSessionAcquireResponse response =
                    acquireResponses.poll();

            if (response == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "No acquire response queued"
                        )
                );
            }

            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletionStage<RedisPlayerSessionRenewResponse>
        renew(
                PlayerSessionLease expected,
                Duration ttl
        ) {
            return CompletableFuture.completedFuture(
                    RedisPlayerSessionRenewResponse.renewed(expected)
            );
        }

        @Override
        public CompletionStage<Boolean> releaseIfOwned(
                PlayerSessionLease expected
        ) {
            return release;
        }
    }

    private static final class CleanupConflictStore
            implements RedisPlayerSessionStore {

        private final PlayerSessionLease lease;
        private final CompletionStage<Boolean> cleanupResult;

        private CleanupConflictStore(
                PlayerSessionLease lease,
                CompletionStage<Boolean> cleanupResult
        ) {
            this.lease = lease;
            this.cleanupResult = cleanupResult;
        }

        @Override
        public CompletionStage<RedisPlayerSessionAcquireResponse>
        acquire(
                PlayerSessionLeaseRequest request,
                Duration ttl
        ) {
            return CompletableFuture.completedFuture(
                    RedisPlayerSessionAcquireResponse.acquired(lease)
            );
        }

        @Override
        public CompletionStage<RedisPlayerSessionRenewResponse>
        renew(
                PlayerSessionLease expected,
                Duration ttl
        ) {
            return CompletableFuture.completedFuture(
                    RedisPlayerSessionRenewResponse
                            .withoutLease(
                                    RedisPlayerSessionRenewStatus
                                            .NOT_FOUND
                            )
            );
        }

        @Override
        public CompletionStage<Boolean> releaseIfOwned(
                PlayerSessionLease expected
        ) {
            return cleanupResult;
        }
    }
}

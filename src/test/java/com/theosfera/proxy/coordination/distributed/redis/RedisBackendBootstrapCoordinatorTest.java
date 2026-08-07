package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendBootstrapAcquireRequest;
import com.theosfera.proxy.coordination.BackendBootstrapAcquireResult;
import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.BackendBootstrapReleaseResult;
import com.theosfera.proxy.coordination.BackendBootstrapRenewResult;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisBackendBootstrapCoordinatorTest {

    private static final Duration TTL = Duration.ofSeconds(45);
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final ProxyMembershipLease MEMBERSHIP_LEASE =
            new ProxyMembershipLease(
                    new ProxyInstanceIdentity(
                            "proxy-1",
                            UUID.randomUUID()
                    ),
                    11L
            );
    private static final BackendBootstrapAcquireRequest REQUEST =
            new BackendBootstrapAcquireRequest(
                    "skyblock-1",
                    REQUEST_ID,
                    PLAYER_ID,
                    MEMBERSHIP_LEASE
            );
    private static final BackendBootstrapLease LEASE =
            new BackendBootstrapLease(
                    "skyblock-1",
                    REQUEST_ID,
                    PLAYER_ID,
                    MEMBERSHIP_LEASE,
                    23L
            );

    @Test
    void delegatesAcquireWithConfiguredTtlAndMapsSuccess() {
        RecordingStore store = new RecordingStore();
        store.acquireResult = CompletableFuture.completedFuture(
                RedisBackendBootstrapAcquireResponse.withLease(
                        RedisBackendBootstrapAcquireStatus.ACQUIRED,
                        LEASE
                )
        );
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        BackendBootstrapAcquireResult result = coordinator
                .acquire(REQUEST)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapAcquireResult.Status.ACQUIRED,
                result.status()
        );
        assertEquals(LEASE, result.acquiredLease().orElseThrow());
        assertSame(REQUEST, store.acquireRequest);
        assertEquals(TTL, store.acquireTtl);
    }

    @Test
    void mapsAcquireRejectionsWithoutLease() {
        RecordingStore store = new RecordingStore();
        store.acquireResult = CompletableFuture.completedFuture(
                RedisBackendBootstrapAcquireResponse.withoutLease(
                        RedisBackendBootstrapAcquireStatus.TARGET_BUSY
                )
        );
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        BackendBootstrapAcquireResult result = coordinator
                .acquire(REQUEST)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapAcquireResult.Status.TARGET_BUSY,
                result.status()
        );
        assertEquals(0, result.acquiredLease().stream().count());
    }

    @Test
    void acquireFailsClosedWhenStoreIsUnavailable() {
        RecordingStore store = new RecordingStore();
        store.acquireResult = CompletableFuture.failedFuture(
                new RuntimeException("redis unavailable")
        );
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        BackendBootstrapAcquireResult result = coordinator
                .acquire(REQUEST)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapAcquireResult.Status.COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void acquireDoesNotHideCorruptRedisState() {
        RecordingStore store = new RecordingStore();
        RedisBackendBootstrapInvalidStateException failure =
                new RedisBackendBootstrapInvalidStateException("corrupt");
        store.acquireResult = CompletableFuture.failedFuture(failure);
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        CompletionException thrown = assertThrows(
                CompletionException.class,
                () -> coordinator.acquire(REQUEST)
                        .toCompletableFuture()
                        .join()
        );

        assertSame(failure, thrown.getCause());
    }

    @Test
    void rejectsMismatchedAcquireLeaseAsCorruptState() {
        RecordingStore store = new RecordingStore();
        BackendBootstrapLease mismatched = new BackendBootstrapLease(
                "skyblock-2",
                REQUEST_ID,
                PLAYER_ID,
                MEMBERSHIP_LEASE,
                24L
        );
        store.acquireResult = CompletableFuture.completedFuture(
                RedisBackendBootstrapAcquireResponse.withLease(
                        RedisBackendBootstrapAcquireStatus.ACQUIRED,
                        mismatched
                )
        );
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        CompletionException thrown = assertThrows(
                CompletionException.class,
                () -> coordinator.acquire(REQUEST)
                        .toCompletableFuture()
                        .join()
        );

        assertEquals(
                RedisBackendBootstrapInvalidStateException.class,
                thrown.getCause().getClass()
        );
    }

    @Test
    void delegatesRenewWithConfiguredTtlAndExactLease() {
        RecordingStore store = new RecordingStore();
        store.renewResult = CompletableFuture.completedFuture(
                RedisBackendBootstrapRenewResponse.renewed(LEASE)
        );
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        BackendBootstrapRenewResult result = coordinator
                .renew(LEASE)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapRenewResult.Status.RENEWED,
                result.status()
        );
        assertEquals(LEASE, result.renewedLease().orElseThrow());
        assertSame(LEASE, store.renewExpected);
        assertEquals(TTL, store.renewTtl);
    }

    @Test
    void renewFailsClosedWhenStoreIsUnavailable() {
        RecordingStore store = new RecordingStore();
        store.renewResult = CompletableFuture.failedFuture(
                new RuntimeException("redis unavailable")
        );
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        BackendBootstrapRenewResult result = coordinator
                .renew(LEASE)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapRenewResult.Status.COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void delegatesReleaseAndMapsStatus() {
        RecordingStore store = new RecordingStore();
        store.releaseResult = CompletableFuture.completedFuture(
                new RedisBackendBootstrapReleaseResponse(
                        RedisBackendBootstrapReleaseStatus.RELEASED
                )
        );
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        BackendBootstrapReleaseResult result = coordinator
                .releaseIfOwned(LEASE)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapReleaseResult.Status.RELEASED,
                result.status()
        );
        assertSame(LEASE, store.releaseExpected);
    }

    @Test
    void releaseFailsClosedWhenStoreIsUnavailable() {
        RecordingStore store = new RecordingStore();
        store.releaseResult = CompletableFuture.failedFuture(
                new RuntimeException("redis unavailable")
        );
        RedisBackendBootstrapCoordinator coordinator =
                new RedisBackendBootstrapCoordinator(store, TTL);

        BackendBootstrapReleaseResult result = coordinator
                .releaseIfOwned(LEASE)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapReleaseResult.Status.COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void rejectsSubMillisecondBootstrapTtl() {
        RecordingStore store = new RecordingStore();

        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisBackendBootstrapCoordinator(
                        store,
                        Duration.ofNanos(1)
                )
        );
    }

    private static final class RecordingStore
            implements RedisBackendBootstrapStore {

        private BackendBootstrapAcquireRequest acquireRequest;
        private Duration acquireTtl;
        private BackendBootstrapLease renewExpected;
        private Duration renewTtl;
        private BackendBootstrapLease releaseExpected;

        private CompletableFuture<RedisBackendBootstrapAcquireResponse>
                acquireResult = CompletableFuture.completedFuture(
                RedisBackendBootstrapAcquireResponse.withoutLease(
                        RedisBackendBootstrapAcquireStatus.TARGET_BUSY
                )
        );
        private CompletableFuture<RedisBackendBootstrapRenewResponse>
                renewResult = CompletableFuture.completedFuture(
                RedisBackendBootstrapRenewResponse.withoutLease(
                        RedisBackendBootstrapRenewStatus.NOT_FOUND
                )
        );
        private CompletableFuture<RedisBackendBootstrapReleaseResponse>
                releaseResult = CompletableFuture.completedFuture(
                new RedisBackendBootstrapReleaseResponse(
                        RedisBackendBootstrapReleaseStatus.NOT_FOUND
                )
        );

        @Override
        public CompletableFuture<RedisBackendBootstrapAcquireResponse> acquire(
                BackendBootstrapAcquireRequest request,
                Duration ttl
        ) {
            acquireRequest = request;
            acquireTtl = ttl;
            return acquireResult;
        }

        @Override
        public CompletableFuture<RedisBackendBootstrapRenewResponse> renew(
                BackendBootstrapLease expected,
                Duration ttl
        ) {
            renewExpected = expected;
            renewTtl = ttl;
            return renewResult;
        }

        @Override
        public CompletableFuture<RedisBackendBootstrapReleaseResponse>
        releaseIfOwned(
                BackendBootstrapLease expected
        ) {
            releaseExpected = expected;
            return releaseResult;
        }
    }
}

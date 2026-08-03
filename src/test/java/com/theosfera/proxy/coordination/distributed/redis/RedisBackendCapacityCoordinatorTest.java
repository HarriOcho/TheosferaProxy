package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.transfer.BackendCapacityReservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisBackendCapacityCoordinatorTest {

    private static final Duration TTL = Duration.ofSeconds(10);
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final BackendCapacityReservation RESERVATION =
            new BackendCapacityReservation(
                    UUID.randomUUID(),
                    PLAYER_ID,
                    "lobby-1"
            );
    private static final PlayerSessionLease LEASE =
            new PlayerSessionLease(
                    new AuthenticatedPlayerSession(
                            PLAYER_ID,
                            "HarriOcho",
                            1000L
                    ),
                    new ProxyInstanceIdentity(
                            "proxy-1",
                            UUID.randomUUID()
                    ),
                    7L
            );
    private static final BackendCapacityReserveRequest REQUEST =
            new BackendCapacityReserveRequest(
                    RESERVATION,
                    LEASE
            );

    @Test
    void delegatesReservationWithConfiguredTtlAndExactLease() {
        RecordingStore store = new RecordingStore();
        store.reserveResult = CompletableFuture.completedFuture(
                BackendCapacityReserveResult.withReservation(
                        BackendCapacityReserveResult.Status.RESERVED,
                        RESERVATION
                )
        );
        RedisBackendCapacityCoordinator coordinator =
                new RedisBackendCapacityCoordinator(store, TTL);

        BackendCapacityReserveResult result = coordinator
                .reserve(REQUEST, 100)
                .toCompletableFuture()
                .join();

        assertEquals(BackendCapacityReserveResult.Status.RESERVED, result.status());
        assertEquals(REQUEST, store.request);
        assertEquals(100, store.capacity);
        assertEquals(TTL, store.ttl);
    }

    @Test
    void failsClosedWhenReservationCoordinationIsUnavailable() {
        RecordingStore store = new RecordingStore();
        store.reserveResult = CompletableFuture.failedFuture(
                new RuntimeException("redis unavailable")
        );
        RedisBackendCapacityCoordinator coordinator =
                new RedisBackendCapacityCoordinator(store, TTL);

        BackendCapacityReserveResult result = coordinator
                .reserve(REQUEST, 100)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void releaseFailsClosedWhenCoordinationIsUnavailable() {
        RecordingStore store = new RecordingStore();
        store.releaseResult = CompletableFuture.failedFuture(
                new RuntimeException("redis unavailable")
        );
        RedisBackendCapacityCoordinator coordinator =
                new RedisBackendCapacityCoordinator(store, TTL);

        assertFalse(
                coordinator.releaseIfOwned(REQUEST)
                        .toCompletableFuture()
                        .join()
        );
    }

    @Test
    void reservedCountDoesNotDegradeToZeroOnFailure() {
        RecordingStore store = new RecordingStore();
        store.countResult = CompletableFuture.failedFuture(
                new RuntimeException("redis unavailable")
        );
        RedisBackendCapacityCoordinator coordinator =
                new RedisBackendCapacityCoordinator(store, TTL);

        assertThrows(
                RuntimeException.class,
                () -> coordinator.reservedCount("lobby-1")
                        .toCompletableFuture()
                        .join()
        );
    }

    @Test
    void delegatesSuccessfulReleaseAndCount() {
        RecordingStore store = new RecordingStore();
        store.releaseResult = CompletableFuture.completedFuture(true);
        store.countResult = CompletableFuture.completedFuture(3);
        RedisBackendCapacityCoordinator coordinator =
                new RedisBackendCapacityCoordinator(store, TTL);

        assertTrue(
                coordinator.releaseIfOwned(REQUEST)
                        .toCompletableFuture()
                        .join()
        );
        assertEquals(
                3,
                coordinator.reservedCount("lobby-1")
                        .toCompletableFuture()
                        .join()
        );
    }

    @Test
    void rejectsSubMillisecondReservationTtl() {
        RecordingStore store = new RecordingStore();

        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisBackendCapacityCoordinator(
                        store,
                        Duration.ofNanos(1)
                )
        );
    }

    private static final class RecordingStore
            implements RedisBackendCapacityStore {

        private BackendCapacityReserveRequest request;
        private int capacity;
        private Duration ttl;
        private CompletableFuture<BackendCapacityReserveResult> reserveResult =
                CompletableFuture.completedFuture(
                        BackendCapacityReserveResult.withoutReservation(
                                BackendCapacityReserveResult.Status.NO_CAPACITY
                        )
                );
        private CompletableFuture<Boolean> releaseResult =
                CompletableFuture.completedFuture(false);
        private CompletableFuture<Integer> countResult =
                CompletableFuture.completedFuture(0);

        @Override
        public CompletableFuture<BackendCapacityReserveResult> reserve(
                BackendCapacityReserveRequest request,
                int capacity,
                Duration ttl
        ) {
            this.request = request;
            this.capacity = capacity;
            this.ttl = ttl;
            return reserveResult;
        }

        @Override
        public CompletableFuture<Boolean> releaseIfOwned(
                BackendCapacityReserveRequest expected
        ) {
            return releaseResult;
        }

        @Override
        public CompletableFuture<Integer> reservedCount(String backendName) {
            return countResult;
        }
    }
}

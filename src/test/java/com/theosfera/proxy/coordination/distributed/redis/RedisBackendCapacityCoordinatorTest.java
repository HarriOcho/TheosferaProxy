package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
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
    private static final BackendCapacityReservation RESERVATION =
            new BackendCapacityReservation(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "lobby-1"
            );

    @Test
    void delegatesReservationWithConfiguredTtl() {
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
                .reserve(RESERVATION, 100)
                .toCompletableFuture()
                .join();

        assertEquals(BackendCapacityReserveResult.Status.RESERVED, result.status());
        assertEquals(RESERVATION, store.reservation);
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
                .reserve(RESERVATION, 100)
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
                coordinator.releaseIfOwned(RESERVATION)
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
                coordinator.releaseIfOwned(RESERVATION)
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

    private static final class RecordingStore
            implements RedisBackendCapacityStore {

        private BackendCapacityReservation reservation;
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
                BackendCapacityReservation reservation,
                int capacity,
                Duration ttl
        ) {
            this.reservation = reservation;
            this.capacity = capacity;
            this.ttl = ttl;
            return reserveResult;
        }

        @Override
        public CompletableFuture<Boolean> releaseIfOwned(
                BackendCapacityReservation expected
        ) {
            return releaseResult;
        }

        @Override
        public CompletableFuture<Integer> reservedCount(String backendName) {
            return countResult;
        }
    }
}

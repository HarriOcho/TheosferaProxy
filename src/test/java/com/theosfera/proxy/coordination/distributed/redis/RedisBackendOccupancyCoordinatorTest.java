package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendOccupancyReadResult;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisBackendOccupancyCoordinatorTest {

    @Test
    void returnsAvailableOccupancyForConfiguredBackend() {
        RedisBackendOccupancyCoordinator coordinator =
                new RedisBackendOccupancyCoordinator(
                        backendName -> CompletableFuture.completedFuture(12),
                        Set.of("lobby-1")
                );

        BackendOccupancyReadResult result = coordinator
                .read("lobby-1")
                .toCompletableFuture()
                .join();

        assertEquals(BackendOccupancyReadResult.Status.AVAILABLE, result.status());
        assertEquals(12, result.occupancy().orElseThrow());
    }

    @Test
    void rejectsBackendOutsideConfiguredSetWithoutCallingStore() {
        RedisBackendOccupancyCoordinator coordinator =
                new RedisBackendOccupancyCoordinator(
                        backendName -> {
                            throw new AssertionError("store must not be called");
                        },
                        Set.of("lobby-1")
                );

        BackendOccupancyReadResult result = coordinator
                .read("skyblock-1")
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendOccupancyReadResult.Status.BACKEND_NOT_FOUND,
                result.status()
        );
    }

    @Test
    void failsClosedWhenRedisReadFails() {
        RedisBackendOccupancyCoordinator coordinator =
                new RedisBackendOccupancyCoordinator(
                        backendName -> CompletableFuture.failedFuture(
                                new IllegalStateException("redis unavailable")
                        ),
                        Set.of("lobby-1")
                );

        BackendOccupancyReadResult result = coordinator
                .read("lobby-1")
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendOccupancyReadResult.Status.COORDINATION_UNAVAILABLE,
                result.status()
        );
    }
}

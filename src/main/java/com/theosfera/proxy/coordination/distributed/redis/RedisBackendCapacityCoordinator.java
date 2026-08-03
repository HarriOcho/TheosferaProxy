package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class RedisBackendCapacityCoordinator
        implements BackendCapacityCoordinator {

    private final RedisBackendCapacityStore store;
    private final Duration reservationTtl;

    public RedisBackendCapacityCoordinator(
            RedisScriptingAsyncCommands<String, String> commands,
            Duration reservationTtl
    ) {
        this(
                new LettuceRedisBackendCapacityStore(
                        commands,
                        RedisBackendOccupancyKeyspace.defaultKeyspace(),
                        RedisBackendCapacityKeyspace.defaultKeyspace(),
                        RedisPlayerSessionKeyspace.defaultKeyspace()
                ),
                reservationTtl
        );
    }

    RedisBackendCapacityCoordinator(
            RedisBackendCapacityStore store,
            Duration reservationTtl
    ) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.reservationTtl = requirePositiveTtl(reservationTtl);
    }

    @Override
    public CompletionStage<BackendCapacityReserveResult> reserve(
            BackendCapacityReserveRequest request,
            int capacity
    ) {
        BackendCapacityReserveRequest nonNullRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }

        return store.reserve(nonNullRequest, capacity, reservationTtl)
                .handle((result, failure) -> {
                    if (failure == null) {
                        return completed(result);
                    }
                    Throwable cause = unwrap(failure);
                    if (cause instanceof RedisBackendCapacityInvalidStateException) {
                        return RedisBackendCapacityCoordinator
                                .<BackendCapacityReserveResult>failed(cause);
                    }
                    return completed(
                            BackendCapacityReserveResult.withoutReservation(
                                    BackendCapacityReserveResult.Status
                                            .COORDINATION_UNAVAILABLE
                            )
                    );
                })
                .thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<Boolean> releaseIfOwned(
            BackendCapacityReserveRequest expected
    ) {
        BackendCapacityReserveRequest nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );

        return store.releaseIfOwned(nonNullExpected)
                .handle((released, failure) -> {
                    if (failure == null) {
                        return completed(released);
                    }
                    Throwable cause = unwrap(failure);
                    if (cause instanceof RedisBackendCapacityInvalidStateException) {
                        return RedisBackendCapacityCoordinator
                                .<Boolean>failed(cause);
                    }
                    return completed(false);
                })
                .thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<Integer> reservedCount(String backendName) {
        String normalized = requireBackendName(backendName);
        return store.reservedCount(normalized)
                .handle((count, failure) -> {
                    if (failure == null) {
                        return completed(count);
                    }
                    Throwable cause = unwrap(failure);
                    if (cause instanceof RedisBackendCapacityInvalidStateException) {
                        return RedisBackendCapacityCoordinator.<Integer>failed(cause);
                    }
                    return RedisBackendCapacityCoordinator.<Integer>failed(
                            new IllegalStateException(
                                    "Redis backend capacity count unavailable",
                                    cause
                            )
                    );
                })
                .thenCompose(stage -> stage);
    }

    private static Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(
                ttl,
                "reservationTtl cannot be null"
        );
        if (nonNullTtl.isZero()
                || nonNullTtl.isNegative()
                || nonNullTtl.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "reservationTtl must be positive and at least one millisecond"
            );
        }
        return nonNullTtl;
    }

    private static String requireBackendName(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("backendName cannot be blank");
        }
        return normalized;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }
}

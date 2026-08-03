package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.BackendOccupancyReadResult;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class RedisBackendOccupancyCoordinator
        implements BackendOccupancyCoordinator {

    private final RedisBackendOccupancyStore store;
    private final Set<String> configuredBackends;

    public RedisBackendOccupancyCoordinator(
            io.lettuce.core.api.async.RedisScriptingAsyncCommands<String, String> commands,
            Set<String> configuredBackends
    ) {
        this(
                new LettuceRedisBackendOccupancyStore(
                        commands,
                        RedisBackendOccupancyKeyspace.defaultKeyspace()
                ),
                configuredBackends
        );
    }

    RedisBackendOccupancyCoordinator(
            RedisBackendOccupancyStore store,
            Set<String> configuredBackends
    ) {
        this.store = Objects.requireNonNull(
                store,
                "store cannot be null"
        );
        this.configuredBackends = Set.copyOf(
                Objects.requireNonNull(
                        configuredBackends,
                        "configuredBackends cannot be null"
                )
        );
    }

    @Override
    public CompletionStage<BackendOccupancyReadResult> read(
            String backendName
    ) {
        String normalized = requireBackendName(backendName);

        if (!configuredBackends.contains(normalized)) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    BackendOccupancyReadResult.unavailable(
                            BackendOccupancyReadResult.Status.BACKEND_NOT_FOUND
                    )
            );
        }

        return store.countPresentPlayers(normalized)
                .handle((count, failure) -> {
                    if (failure == null) {
                        return BackendOccupancyReadResult.available(count);
                    }

                    unwrap(failure);
                    return BackendOccupancyReadResult.unavailable(
                            BackendOccupancyReadResult.Status
                                    .COORDINATION_UNAVAILABLE
                    );
                });
    }

    private static String requireBackendName(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
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
}

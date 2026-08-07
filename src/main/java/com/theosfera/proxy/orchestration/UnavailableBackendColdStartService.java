package com.theosfera.proxy.orchestration;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Fail-closed placeholder used until a concrete fenced orchestration actuator
 * is configured. It never performs a local or unfenced startup fallback.
 */
public final class UnavailableBackendColdStartService
        implements BackendColdStartService {

    @Override
    public CompletionStage<BackendColdStartResult> start(
            String targetBackendName,
            UUID requestId,
            UUID playerId
    ) {
        String target = Objects.requireNonNull(
                targetBackendName,
                "targetBackendName cannot be null"
        ).trim();
        if (target.isEmpty()) {
            throw new IllegalArgumentException(
                    "targetBackendName cannot be blank"
            );
        }
        Objects.requireNonNull(requestId, "requestId cannot be null");
        Objects.requireNonNull(playerId, "playerId cannot be null");

        return CompletableFuture.completedFuture(
                BackendColdStartResult.of(
                        BackendColdStartResult.Status.COORDINATION_UNAVAILABLE,
                        target
                )
        );
    }
}

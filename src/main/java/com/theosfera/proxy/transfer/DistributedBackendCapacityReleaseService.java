package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Fail-closed adapter for exact distributed capacity releases.
 *
 * <p>A caller may only treat a {@code true} result as proof that the exact
 * reservation is no longer owned. Null stages, exceptions and false results
 * are deliberately collapsed to {@code false}; retry callers must not infer
 * available capacity from an unconfirmed release.</p>
 */
public final class DistributedBackendCapacityReleaseService {

    private final BackendCapacityCoordinator capacityCoordinator;
    private final Logger logger;

    public DistributedBackendCapacityReleaseService(
            BackendCapacityCoordinator capacityCoordinator,
            Logger logger
    ) {
        this.capacityCoordinator = Objects.requireNonNull(
                capacityCoordinator,
                "capacityCoordinator cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public CompletionStage<Boolean> releaseIfOwned(
            BackendCapacityReserveRequest request
    ) {
        BackendCapacityReserveRequest nonNullRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );

        final CompletionStage<Boolean> stage;
        try {
            stage = capacityCoordinator.releaseIfOwned(nonNullRequest);
        } catch (RuntimeException exception) {
            logUnconfirmed(nonNullRequest, exception);
            return CompletableFuture.completedFuture(false);
        }

        if (stage == null) {
            logUnconfirmed(nonNullRequest, null);
            return CompletableFuture.completedFuture(false);
        }

        return stage.handle((released, failure) -> {
            if (failure != null) {
                logUnconfirmed(nonNullRequest, failure);
                return false;
            }

            if (!Boolean.TRUE.equals(released)) {
                logUnconfirmed(nonNullRequest, null);
                return false;
            }

            return true;
        });
    }

    private void logUnconfirmed(
            BackendCapacityReserveRequest request,
            Throwable failure
    ) {
        if (failure == null) {
            logger.warn(
                    "No se confirmo el release exacto de capacidad distribuida para {} en {}; no se permitira retry hasta una nueva operacion autoritativa.",
                    request.reservation().playerId(),
                    request.reservation().backendName()
            );
            return;
        }

        logger.warn(
                "Fallo el release exacto de capacidad distribuida para {} en {}; no se permitira retry.",
                request.reservation().playerId(),
                request.reservation().backendName(),
                failure
        );
    }
}

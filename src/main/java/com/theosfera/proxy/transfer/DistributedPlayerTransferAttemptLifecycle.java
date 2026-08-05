package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Owns lifecycle transitions for one distributed transfer attempt.
 *
 * <p>Failed attempts remove matching local state and release their exact Redis
 * capacity reservation. Successful connections never release capacity here;
 * they transfer the reservation into the presence handoff lifecycle.</p>
 */
public final class DistributedPlayerTransferAttemptLifecycle {

    public enum SuccessfulConnectionDisposition {
        HANDOFF_REGISTERED,
        HANDOFF_ALREADY_REGISTERED,
        HANDOFF_TTL_FALLBACK,
        LATE_RESULT
    }

    public record CleanupResult(
            boolean transferMatched,
            boolean capacityReleased
    ) {
    }

    private final PendingPlayerTransferRegistry transferRegistry;
    private final BackendBootstrapRegistry bootstrapRegistry;
    private final DistributedBackendCapacityReleaseService releaseService;
    private final BackendCapacityHandoffService handoffService;
    private final Logger logger;

    public DistributedPlayerTransferAttemptLifecycle(
            PendingPlayerTransferRegistry transferRegistry,
            BackendBootstrapRegistry bootstrapRegistry,
            DistributedBackendCapacityReleaseService releaseService,
            BackendCapacityHandoffService handoffService,
            Logger logger
    ) {
        this.transferRegistry = Objects.requireNonNull(
                transferRegistry,
                "transferRegistry cannot be null"
        );
        this.bootstrapRegistry = Objects.requireNonNull(
                bootstrapRegistry,
                "bootstrapRegistry cannot be null"
        );
        this.releaseService = Objects.requireNonNull(
                releaseService,
                "releaseService cannot be null"
        );
        this.handoffService = Objects.requireNonNull(
                handoffService,
                "handoffService cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public CompletionStage<CleanupResult> cleanupFailedAttempt(
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendBootstrapReservation bootstrapReservation
    ) {
        PendingPlayerTransfer nonNullTransfer = Objects.requireNonNull(
                transfer,
                "transfer cannot be null"
        );
        BackendCapacityReserveRequest nonNullCapacityRequest =
                Objects.requireNonNull(
                        capacityRequest,
                        "capacityRequest cannot be null"
                );

        Optional<PendingPlayerTransfer> removed =
                transferRegistry.removeIfMatches(nonNullTransfer);

        if (bootstrapReservation != null) {
            bootstrapRegistry.removeIfMatches(bootstrapReservation);
        }

        final CompletionStage<Boolean> releaseStage;
        try {
            releaseStage = releaseService.releaseIfOwned(
                    nonNullCapacityRequest
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo iniciar cleanup distribuido para {} en {}.",
                    nonNullTransfer.playerId(),
                    nonNullTransfer.targetBackendName(),
                    exception
            );
            return CompletableFuture.completedFuture(
                    new CleanupResult(removed.isPresent(), false)
            );
        }

        if (releaseStage == null) {
            logger.warn(
                    "El cleanup distribuido devolvio un stage nulo para {} en {}.",
                    nonNullTransfer.playerId(),
                    nonNullTransfer.targetBackendName()
            );
            return CompletableFuture.completedFuture(
                    new CleanupResult(removed.isPresent(), false)
            );
        }

        return releaseStage.handle((released, failure) -> {
            if (failure != null) {
                logger.warn(
                        "Fallo el cleanup distribuido para {} en {}.",
                        nonNullTransfer.playerId(),
                        nonNullTransfer.targetBackendName(),
                        failure
                );
                return new CleanupResult(
                        removed.isPresent(),
                        false
                );
            }

            return new CleanupResult(
                    removed.isPresent(),
                    Boolean.TRUE.equals(released)
            );
        });
    }

    public SuccessfulConnectionDisposition completeSuccessfulConnection(
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest
    ) {
        PendingPlayerTransfer nonNullTransfer = Objects.requireNonNull(
                transfer,
                "transfer cannot be null"
        );
        BackendCapacityReserveRequest nonNullCapacityRequest =
                Objects.requireNonNull(
                        capacityRequest,
                        "capacityRequest cannot be null"
                );

        if (transferRegistry.removeIfMatches(nonNullTransfer).isEmpty()) {
            return SuccessfulConnectionDisposition.LATE_RESULT;
        }

        final BackendCapacityHandoffRegistrationResult handoffResult;
        try {
            handoffResult = handoffService.registerAfterConnectionSuccess(
                    nonNullCapacityRequest
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "Conexion a {} confirmada para {}, pero no se pudo registrar el handoff de capacidad; la reserva Redis dependera de TTL.",
                    nonNullTransfer.targetBackendName(),
                    nonNullTransfer.playerId(),
                    exception
            );
            return SuccessfulConnectionDisposition.HANDOFF_TTL_FALLBACK;
        }

        return switch (handoffResult) {
            case REGISTERED ->
                    SuccessfulConnectionDisposition.HANDOFF_REGISTERED;
            case ALREADY_REGISTERED ->
                    SuccessfulConnectionDisposition
                            .HANDOFF_ALREADY_REGISTERED;
            case PLAYER_BUSY, REQUEST_ID_CONFLICT -> {
                logger.warn(
                        "Conexion a {} confirmada para {}, pero el handoff de capacidad fue rechazado por {}; no se liberara la reserva y TTL actuara como fallback.",
                        nonNullTransfer.targetBackendName(),
                        nonNullTransfer.playerId(),
                        handoffResult
                );
                yield SuccessfulConnectionDisposition.HANDOFF_TTL_FALLBACK;
            }
        };
    }
}

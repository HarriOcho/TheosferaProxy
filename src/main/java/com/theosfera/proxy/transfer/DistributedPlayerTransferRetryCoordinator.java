package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Non-blocking transfer retry coordinator backed by distributed capacity.
 *
 * <p>Retries are only allowed after an exact distributed capacity release is
 * positively confirmed. A successful connection never releases capacity
 * directly; ownership is transferred to {@link BackendCapacityHandoffService}
 * until authoritative destination presence is confirmed.</p>
 */
public final class DistributedPlayerTransferRetryCoordinator {

    private final BackendBootstrapRegistry bootstrapRegistry;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final DistributedPlayerTransferTargetAllocationService allocationService;
    private final PlayerTransferExecutor transferExecutor;
    private final DistributedBackendCapacityReleaseService capacityReleaseService;
    private final BackendCapacityHandoffService handoffService;
    private final Logger logger;

    public DistributedPlayerTransferRetryCoordinator(
            BackendBootstrapRegistry bootstrapRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            DistributedPlayerTransferTargetAllocationService allocationService,
            PlayerTransferExecutor transferExecutor,
            DistributedBackendCapacityReleaseService capacityReleaseService,
            BackendCapacityHandoffService handoffService,
            Logger logger
    ) {
        this.bootstrapRegistry = Objects.requireNonNull(
                bootstrapRegistry,
                "bootstrapRegistry cannot be null"
        );
        this.transferRegistry = Objects.requireNonNull(
                transferRegistry,
                "transferRegistry cannot be null"
        );
        this.allocationService = Objects.requireNonNull(
                allocationService,
                "allocationService cannot be null"
        );
        this.transferExecutor = Objects.requireNonNull(
                transferExecutor,
                "transferExecutor cannot be null"
        );
        this.capacityReleaseService = Objects.requireNonNull(
                capacityReleaseService,
                "capacityReleaseService cannot be null"
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

    public void start(TransferRetryRequest request) {
        attempt(
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                ),
                Set.of(),
                null
        );
    }

    private void attempt(
            TransferRetryRequest request,
            Set<String> excludedServerNames,
            TerminalFailure lastFailure
    ) {
        Set<String> exclusions = Set.copyOf(excludedServerNames);

        final CompletionStage<DistributedPlayerTransferTargetAllocation> stage;
        try {
            stage = allocationService.allocate(
                    request.player(),
                    request.requestId(),
                    request.sourceBackendName(),
                    request.targetBackendType(),
                    request.requestedAt(),
                    exclusions
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo iniciar la allocation distribuida de transferencia para {}.",
                    request.playerId(),
                    exception
            );
            finishFailClosed(request, lastFailure);
            return;
        }

        if (stage == null) {
            logger.warn(
                    "La allocation distribuida de transferencia devolvio un stage nulo para {}.",
                    request.playerId()
            );
            finishFailClosed(request, lastFailure);
            return;
        }

        stage.whenComplete((allocation, failure) -> {
            if (failure != null || allocation == null) {
                if (failure != null) {
                    logger.warn(
                            "Fallo la allocation distribuida de transferencia para {}.",
                            request.playerId(),
                            failure
                    );
                }
                finishFailClosed(request, lastFailure);
                return;
            }

            handleAllocation(
                    request,
                    exclusions,
                    lastFailure,
                    allocation
            );
        });
    }

    private void handleAllocation(
            TransferRetryRequest request,
            Set<String> exclusions,
            TerminalFailure lastFailure,
            DistributedPlayerTransferTargetAllocation allocation
    ) {
        if (allocation.isSameTarget()) {
            if (lastFailure != null) {
                finish(request, lastFailure);
            } else {
                request.sameTargetHandler().run();
            }
            return;
        }

        if (allocation.isRegistrationRejected()) {
            if (lastFailure != null) {
                finish(request, lastFailure);
            } else {
                request.registrationRejectedHandler()
                        .accept(allocation.registrationResult());
            }
            return;
        }

        if (allocation.isCapacityRejected()) {
            if (lastFailure != null) {
                finish(request, lastFailure);
            } else {
                request.capacityRejectedHandler()
                        .accept(allocation.capacityStatus());
            }
            return;
        }

        TransferTargetResolution resolution = allocation.targetResolution();
        if (!allocation.isAllocated()) {
            if (lastFailure != null) {
                finish(request, lastFailure);
            } else {
                request.unavailableHandler().accept(resolution);
            }
            return;
        }

        PendingPlayerTransfer transfer = allocation.requireTransfer();
        BackendCapacityReserveRequest capacityRequest =
                allocation.requireCapacityRequest();
        RegisteredServer target = resolution
                .resolvedTarget()
                .orElseThrow();
        String targetBackendName = target.getServerInfo().getName();

        if (exclusions.contains(targetBackendName)) {
            cleanupWithoutRetry(
                    request,
                    transfer,
                    capacityRequest,
                    null,
                    lastFailure
            );
            return;
        }

        BackendBootstrapReservation bootstrapReservation =
                resolution.requiresBootstrap()
                        ? new BackendBootstrapReservation(
                        targetBackendName,
                        transfer.requestId(),
                        transfer.playerId(),
                        transfer.requestedAt()
                )
                        : null;

        if (bootstrapReservation != null) {
            reserveBootstrap(
                    request,
                    exclusions,
                    lastFailure,
                    transfer,
                    capacityRequest,
                    bootstrapReservation,
                    target
            );
            return;
        }

        executeAttempt(
                request,
                exclusions,
                lastFailure,
                transfer,
                capacityRequest,
                null,
                target
        );
    }

    private void reserveBootstrap(
            TransferRetryRequest request,
            Set<String> exclusions,
            TerminalFailure lastFailure,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendBootstrapReservation bootstrapReservation,
            RegisteredServer target
    ) {
        final BackendBootstrapRegistrationResult result;
        try {
            result = bootstrapRegistry.register(bootstrapReservation);
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo registrar bootstrap para {}.",
                    bootstrapReservation.targetBackendName(),
                    exception
            );
            cleanupWithoutRetry(
                    request,
                    transfer,
                    capacityRequest,
                    null,
                    lastFailure
            );
            return;
        }

        if (result == BackendBootstrapRegistrationResult.RESERVED) {
            request.bootstrapReservedHandler().accept(bootstrapReservation);
            executeAttempt(
                    request,
                    exclusions,
                    lastFailure,
                    transfer,
                    capacityRequest,
                    bootstrapReservation,
                    target
            );
            return;
        }

        Optional<PendingPlayerTransfer> removed =
                transferRegistry.removeIfMatches(transfer);

        capacityReleaseService.releaseIfOwned(capacityRequest)
                .whenComplete((released, failure) -> {
                    if (removed.isEmpty()) {
                        request.lateResultHandler().accept(transfer);
                        return;
                    }

                    if (failure != null || !Boolean.TRUE.equals(released)) {
                        finishFailClosed(request, lastFailure);
                        return;
                    }

                    switch (result) {
                        case TARGET_BUSY -> attempt(
                                request,
                                withExcluded(
                                        exclusions,
                                        transfer.targetBackendName()
                                ),
                                TerminalFailure.bootstrap(result)
                        );
                        case REQUEST_ID_CONFLICT, ALREADY_RESERVED ->
                                finish(
                                        request,
                                        TerminalFailure.bootstrap(result)
                                );
                        case RESERVED -> throw new IllegalStateException(
                                "reserved bootstrap was handled earlier"
                        );
                    }
                });
    }

    private void executeAttempt(
            TransferRetryRequest request,
            Set<String> exclusions,
            TerminalFailure lastFailure,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendBootstrapReservation bootstrapReservation,
            RegisteredServer target
    ) {
        final CompletionStage<PlayerTransferCompletion> stage;
        try {
            stage = transferExecutor.execute(request.player(), target);
        } catch (RuntimeException exception) {
            completeAttempt(
                    request,
                    exclusions,
                    lastFailure,
                    transfer,
                    capacityRequest,
                    bootstrapReservation,
                    PlayerTransferCompletion.failed()
            );
            return;
        }

        if (stage == null) {
            completeAttempt(
                    request,
                    exclusions,
                    lastFailure,
                    transfer,
                    capacityRequest,
                    bootstrapReservation,
                    PlayerTransferCompletion.failed()
            );
            return;
        }

        stage.whenComplete((completion, failure) ->
                completeAttempt(
                        request,
                        exclusions,
                        lastFailure,
                        transfer,
                        capacityRequest,
                        bootstrapReservation,
                        failure == null && completion != null
                                ? completion
                                : PlayerTransferCompletion.failed()
                )
        );
    }

    private void completeAttempt(
            TransferRetryRequest request,
            Set<String> exclusions,
            TerminalFailure lastFailure,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendBootstrapReservation bootstrapReservation,
            PlayerTransferCompletion completion
    ) {
        if (completion.status() == TransferResultStatus.SUCCESS) {
            completeSuccessfulConnection(
                    request,
                    transfer,
                    capacityRequest,
                    completion
            );
            return;
        }

        Optional<PendingPlayerTransfer> removed =
                transferRegistry.removeIfMatches(transfer);

        if (bootstrapReservation != null) {
            bootstrapRegistry.removeIfMatches(bootstrapReservation);
        }

        capacityReleaseService.releaseIfOwned(capacityRequest)
                .whenComplete((released, failure) -> {
                    if (removed.isEmpty()) {
                        request.lateResultHandler().accept(transfer);
                        return;
                    }

                    if (failure != null || !Boolean.TRUE.equals(released)) {
                        finish(request, completion);
                        return;
                    }

                    if (completion.status()
                            == TransferResultStatus.TIMED_OUT) {
                        finish(request, completion);
                        return;
                    }

                    attempt(
                            request,
                            withExcluded(
                                    exclusions,
                                    transfer.targetBackendName()
                            ),
                            TerminalFailure.completion(completion)
                    );
                });
    }

    private void completeSuccessfulConnection(
            TransferRetryRequest request,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            PlayerTransferCompletion completion
    ) {
        Optional<PendingPlayerTransfer> removed =
                transferRegistry.removeIfMatches(transfer);

        if (removed.isEmpty()) {
            request.lateResultHandler().accept(transfer);
            return;
        }

        BackendCapacityHandoffRegistrationResult handoffResult;
        try {
            handoffResult = handoffService
                    .registerAfterConnectionSuccess(capacityRequest);
        } catch (RuntimeException exception) {
            logger.warn(
                    "Conexion a {} confirmada para {}, pero no se pudo registrar el handoff de capacidad; la reserva Redis dependera de TTL.",
                    transfer.targetBackendName(),
                    transfer.playerId(),
                    exception
            );
            finish(request, completion);
            return;
        }

        switch (handoffResult) {
            case REGISTERED, ALREADY_REGISTERED -> logger.debug(
                    "Reserva distribuida transferida a handoff para {} en {}.",
                    transfer.playerId(),
                    transfer.targetBackendName()
            );
            case PLAYER_BUSY, REQUEST_ID_CONFLICT -> logger.warn(
                    "Conexion a {} confirmada para {}, pero el handoff de capacidad fue rechazado por {}; no se liberara la reserva y TTL actuara como fallback.",
                    transfer.targetBackendName(),
                    transfer.playerId(),
                    handoffResult
            );
        }

        finish(request, completion);
    }

    private void cleanupWithoutRetry(
            TransferRetryRequest request,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendBootstrapReservation bootstrapReservation,
            TerminalFailure lastFailure
    ) {
        Optional<PendingPlayerTransfer> removed =
                transferRegistry.removeIfMatches(transfer);

        if (bootstrapReservation != null) {
            bootstrapRegistry.removeIfMatches(bootstrapReservation);
        }

        capacityReleaseService.releaseIfOwned(capacityRequest)
                .whenComplete((released, failure) -> {
                    if (removed.isEmpty()) {
                        request.lateResultHandler().accept(transfer);
                        return;
                    }
                    finishFailClosed(request, lastFailure);
                });
    }

    private void finish(
            TransferRetryRequest request,
            TerminalFailure failure
    ) {
        switch (failure.type()) {
            case CONNECTION_COMPLETION ->
                    request.completionHandler()
                            .accept(failure.completion());
            case BOOTSTRAP_REJECTION ->
                    request.bootstrapRejectedHandler()
                            .accept(failure.bootstrapRejection());
        }
    }

    private void finish(
            TransferRetryRequest request,
            PlayerTransferCompletion completion
    ) {
        request.completionHandler().accept(completion);
    }

    private void finishFailClosed(
            TransferRetryRequest request,
            TerminalFailure lastFailure
    ) {
        if (lastFailure != null) {
            finish(request, lastFailure);
            return;
        }
        finish(request, PlayerTransferCompletion.failed());
    }

    private Set<String> withExcluded(
            Set<String> exclusions,
            String targetBackendName
    ) {
        Set<String> nextExclusions = new HashSet<>(exclusions);
        nextExclusions.add(targetBackendName);
        return Set.copyOf(nextExclusions);
    }

    public record TransferRetryRequest(
            UUID requestId,
            UUID playerId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt,
            Player player,
            Runnable sameTargetHandler,
            Consumer<PlayerTransferRegistrationResult>
                    registrationRejectedHandler,
            Consumer<TransferTargetResolution> unavailableHandler,
            Consumer<BackendCapacityReserveResult.Status>
                    capacityRejectedHandler,
            Consumer<BackendBootstrapRegistrationResult>
                    bootstrapRejectedHandler,
            Consumer<BackendBootstrapReservation> bootstrapReservedHandler,
            Consumer<PlayerTransferCompletion> completionHandler,
            Consumer<PendingPlayerTransfer> lateResultHandler
    ) {
        public TransferRetryRequest {
            Objects.requireNonNull(requestId, "requestId cannot be null");
            Objects.requireNonNull(playerId, "playerId cannot be null");
            Objects.requireNonNull(
                    sourceBackendName,
                    "sourceBackendName cannot be null"
            );
            Objects.requireNonNull(
                    targetBackendType,
                    "targetBackendType cannot be null"
            );
            Objects.requireNonNull(player, "player cannot be null");
            Objects.requireNonNull(
                    sameTargetHandler,
                    "sameTargetHandler cannot be null"
            );
            Objects.requireNonNull(
                    registrationRejectedHandler,
                    "registrationRejectedHandler cannot be null"
            );
            Objects.requireNonNull(
                    unavailableHandler,
                    "unavailableHandler cannot be null"
            );
            Objects.requireNonNull(
                    capacityRejectedHandler,
                    "capacityRejectedHandler cannot be null"
            );
            Objects.requireNonNull(
                    bootstrapRejectedHandler,
                    "bootstrapRejectedHandler cannot be null"
            );
            Objects.requireNonNull(
                    bootstrapReservedHandler,
                    "bootstrapReservedHandler cannot be null"
            );
            Objects.requireNonNull(
                    completionHandler,
                    "completionHandler cannot be null"
            );
            Objects.requireNonNull(
                    lateResultHandler,
                    "lateResultHandler cannot be null"
            );

            if (!playerId.equals(player.getUniqueId())) {
                throw new IllegalArgumentException(
                        "playerId must match player identity"
                );
            }
        }
    }

    private enum TerminalFailureType {
        CONNECTION_COMPLETION,
        BOOTSTRAP_REJECTION
    }

    private record TerminalFailure(
            TerminalFailureType type,
            PlayerTransferCompletion completion,
            BackendBootstrapRegistrationResult bootstrapRejection
    ) {
        private TerminalFailure {
            Objects.requireNonNull(type, "type cannot be null");
            switch (type) {
                case CONNECTION_COMPLETION -> {
                    Objects.requireNonNull(
                            completion,
                            "completion cannot be null"
                    );
                    if (bootstrapRejection != null) {
                        throw new IllegalArgumentException(
                                "connection failure cannot have bootstrap rejection"
                        );
                    }
                }
                case BOOTSTRAP_REJECTION -> {
                    Objects.requireNonNull(
                            bootstrapRejection,
                            "bootstrapRejection cannot be null"
                    );
                    if (completion != null) {
                        throw new IllegalArgumentException(
                                "bootstrap rejection cannot have completion"
                        );
                    }
                }
            }
        }

        private static TerminalFailure completion(
                PlayerTransferCompletion completion
        ) {
            return new TerminalFailure(
                    TerminalFailureType.CONNECTION_COMPLETION,
                    Objects.requireNonNull(completion),
                    null
            );
        }

        private static TerminalFailure bootstrap(
                BackendBootstrapRegistrationResult rejection
        ) {
            return new TerminalFailure(
                    TerminalFailureType.BOOTSTRAP_REJECTION,
                    null,
                    Objects.requireNonNull(rejection)
            );
        }
    }
}

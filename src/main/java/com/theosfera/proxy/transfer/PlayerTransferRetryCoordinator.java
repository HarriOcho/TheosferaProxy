package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class PlayerTransferRetryCoordinator {

    private final BackendBootstrapRegistry bootstrapRegistry;
    private final TransferTargetResolver targetResolver;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final PlayerTransferTargetAllocationService allocationService;
    private final PlayerTransferExecutor transferExecutor;

    public PlayerTransferRetryCoordinator(
            BackendBootstrapRegistry bootstrapRegistry,
            TransferTargetResolver targetResolver,
            PendingPlayerTransferRegistry transferRegistry,
            PlayerTransferTargetAllocationService allocationService,
            PlayerTransferExecutor transferExecutor
    ) {
        this.bootstrapRegistry = Objects.requireNonNull(
                bootstrapRegistry,
                "bootstrapRegistry cannot be null"
        );

        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
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

        PlayerTransferTargetAllocation allocation =
                allocate(
                        request,
                        exclusions,
                        lastFailure
                );

        if (allocation == null) {
            return;
        }

        if (allocation.isSameTarget()) {
            if (lastFailure != null) {
                finish(
                        request,
                        lastFailure
                );
            } else {
                request.sameTargetHandler().run();
            }
            return;
        }

        if (allocation.isRegistrationRejected()) {
            if (lastFailure != null) {
                finish(
                        request,
                        lastFailure
                );
            } else {
                request.registrationRejectedHandler()
                        .accept(allocation.registrationResult());
            }
            return;
        }

        TransferTargetResolution targetResolution =
                allocation.targetResolution();

        if (!allocation.isAllocated()) {
            if (lastFailure != null) {
                finish(
                        request,
                        lastFailure
                );
            } else {
                request.unavailableHandler()
                        .accept(targetResolution);
            }
            return;
        }

        PendingPlayerTransfer transfer =
                allocation.requireTransfer();

        BackendCapacityReservation capacityReservation =
                allocation.requireCapacityReservation();

        RegisteredServer target =
                targetResolution
                        .resolvedTarget()
                        .orElseThrow();

        String targetBackendName =
                target.getServerInfo().getName();

        if (exclusions.contains(targetBackendName)) {
            transferRegistryCleanup(
                    transfer,
                    capacityReservation,
                    null,
                    false
            );
            finishFailClosed(
                    request,
                    lastFailure
            );
            return;
        }

        BackendBootstrapReservation bootstrapReservation =
                targetResolution.requiresBootstrap()
                        ? new BackendBootstrapReservation(
                        targetBackendName,
                        transfer.requestId(),
                        transfer.playerId(),
                        transfer.requestedAt()
                )
                        : null;

        if (bootstrapReservation != null
                && !reserveBootstrap(
                request,
                transfer,
                capacityReservation,
                bootstrapReservation,
                exclusions
        )) {
            return;
        }

        try {
            transferExecutor
                    .execute(request.player(), target)
                    .whenComplete(
                            (completion, throwable) ->
                                    completeAttempt(
                                            request,
                                            exclusions,
                                            transfer,
                                            capacityReservation,
                                            bootstrapReservation,
                                            completion,
                                            throwable
                                    )
                    );
        } catch (RuntimeException exception) {
            completeAttempt(
                    request,
                    exclusions,
                    transfer,
                    capacityReservation,
                    bootstrapReservation,
                    PlayerTransferCompletion.failed(),
                    exception
            );
        }
    }

    private boolean reserveBootstrap(
            TransferRetryRequest request,
            PendingPlayerTransfer transfer,
            BackendCapacityReservation capacityReservation,
            BackendBootstrapReservation reservation,
            Set<String> exclusions
    ) {
        BackendBootstrapRegistrationResult registrationResult =
                bootstrapRegistry.register(reservation);

        if (registrationResult
                == BackendBootstrapRegistrationResult.RESERVED) {
            request.bootstrapReservedHandler()
                    .accept(reservation);
            return true;
        }

        Optional<PendingPlayerTransfer> removed =
                transferRegistryCleanup(
                        transfer,
                        capacityReservation,
                        null
                );

        if (removed.isEmpty()) {
            request.lateResultHandler()
                    .accept(transfer);
            return false;
        }

        switch (registrationResult) {
            case TARGET_BUSY ->
                    attempt(
                            request,
                            withExcluded(
                                    exclusions,
                                    transfer.targetBackendName()
                            ),
                            TerminalFailure.bootstrap(registrationResult)
                    );
            case REQUEST_ID_CONFLICT, ALREADY_RESERVED ->
                    finish(
                            request,
                            TerminalFailure.bootstrap(registrationResult)
                    );
            case RESERVED ->
                    throw new IllegalStateException(
                            "reserved bootstrap was handled earlier"
                    );
        }

        return false;
    }

    private PlayerTransferTargetAllocation allocate(
            TransferRetryRequest request,
            Set<String> exclusions,
            TerminalFailure lastFailure
    ) {
        try {
            return allocationService.allocate(
                    request.requestId(),
                    request.playerId(),
                    request.sourceBackendName(),
                    request.targetBackendType(),
                    request.requestedAt(),
                    exclusions
            );
        } catch (
                TransferTargetResolutionContractViolationException exception
        ) {
            finishFailClosed(
                    request,
                    lastFailure
            );
            return null;
        } catch (RuntimeException exception) {
            if (lastFailure != null) {
                finish(
                        request,
                        lastFailure
                );
                return null;
            }

            throw exception;
        }
    }

    private void completeAttempt(
            TransferRetryRequest request,
            Set<String> exclusions,
            PendingPlayerTransfer transfer,
            BackendCapacityReservation capacityReservation,
            BackendBootstrapReservation bootstrapReservation,
            PlayerTransferCompletion completion,
            Throwable throwable
    ) {
        PlayerTransferCompletion safeCompletion =
                throwable == null && completion != null
                        ? completion
                        : PlayerTransferCompletion.failed();

        Optional<PendingPlayerTransfer> removed =
                transferRegistryCleanup(
                        transfer,
                        capacityReservation,
                        bootstrapReservation,
                        safeCompletion.status()
                                == TransferResultStatus.SUCCESS
                );

        if (removed.isEmpty()) {
            request.lateResultHandler()
                    .accept(transfer);
            return;
        }

        if (safeCompletion.status()
                == TransferResultStatus.SUCCESS) {
            finish(
                    request,
                    safeCompletion
            );
            return;
        }

        if (safeCompletion.status()
                == TransferResultStatus.TIMED_OUT) {
            finish(
                    request,
                    safeCompletion
            );
            return;
        }

        attempt(
                request,
                withExcluded(
                        exclusions,
                        transfer.targetBackendName()
                ),
                TerminalFailure.completion(safeCompletion)
        );
    }

    private Optional<PendingPlayerTransfer> transferRegistryCleanup(
            PendingPlayerTransfer transfer,
            BackendCapacityReservation capacityReservation,
            BackendBootstrapReservation bootstrapReservation
    ) {
        return transferRegistryCleanup(
                transfer,
                capacityReservation,
                bootstrapReservation,
                true
        );
    }

    private Optional<PendingPlayerTransfer> transferRegistryCleanup(
            PendingPlayerTransfer transfer,
            BackendCapacityReservation capacityReservation,
            BackendBootstrapReservation bootstrapReservation,
            boolean keepBootstrapOnMatchedSuccess
    ) {
        Optional<PendingPlayerTransfer> removed =
                transferRegistry.removeIfMatches(transfer);

        targetResolver.releaseCapacity(capacityReservation);

        boolean keepBootstrap =
                removed.isPresent() && keepBootstrapOnMatchedSuccess;

        if (!keepBootstrap && bootstrapReservation != null) {
            bootstrapRegistry.removeIfMatches(bootstrapReservation);
        }

        return removed;
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
        request.completionHandler()
                .accept(completion);
    }

    private void finishFailClosed(
            TransferRetryRequest request,
            TerminalFailure lastFailure
    ) {
        if (lastFailure != null) {
            finish(
                    request,
                    lastFailure
            );
            return;
        }

        request.completionHandler()
                .accept(PlayerTransferCompletion.failed());
    }

    private Set<String> withExcluded(
            Set<String> exclusions,
            String targetBackendName
    ) {
        Set<String> nextExclusions =
                new HashSet<>(exclusions);

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
            Consumer<BackendBootstrapRegistrationResult>
                    bootstrapRejectedHandler,
            Consumer<BackendBootstrapReservation> bootstrapReservedHandler,
            Consumer<PlayerTransferCompletion> completionHandler,
            Consumer<PendingPlayerTransfer> lateResultHandler
    ) {
        public TransferRetryRequest {
            Objects.requireNonNull(
                    requestId,
                    "requestId cannot be null"
            );
            Objects.requireNonNull(
                    playerId,
                    "playerId cannot be null"
            );
            Objects.requireNonNull(
                    sourceBackendName,
                    "sourceBackendName cannot be null"
            );
            Objects.requireNonNull(
                    targetBackendType,
                    "targetBackendType cannot be null"
            );
            Objects.requireNonNull(
                    player,
                    "player cannot be null"
            );
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
            Objects.requireNonNull(
                    type,
                    "type cannot be null"
            );

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
                    completion,
                    null
            );
        }

        private static TerminalFailure bootstrap(
                BackendBootstrapRegistrationResult rejection
        ) {
            return new TerminalFailure(
                    TerminalFailureType.BOOTSTRAP_REJECTION,
                    null,
                    rejection
            );
        }
    }
}

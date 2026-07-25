package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PlayerTransferTargetAllocationService {

    private final TransferTargetResolver targetResolver;
    private final PendingPlayerTransferRegistry transferRegistry;

    public PlayerTransferTargetAllocationService(
            TransferTargetResolver targetResolver,
            PendingPlayerTransferRegistry transferRegistry
    ) {
        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
        );
        this.transferRegistry = Objects.requireNonNull(
                transferRegistry,
                "transferRegistry cannot be null"
        );
    }

    public PlayerTransferTargetAllocation allocate(
            UUID requestId,
            UUID playerId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt
    ) {
        return allocate(
                requestId,
                playerId,
                sourceBackendName,
                targetBackendType,
                requestedAt,
                Set.of()
        );
    }

    public PlayerTransferTargetAllocation allocate(
            UUID requestId,
            UUID playerId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt,
            Set<String> initialExcludedServerNames
    ) {
        UUID nonNullRequestId = Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );
        UUID nonNullPlayerId = Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );
        String nonNullSource = Objects.requireNonNull(
                sourceBackendName,
                "sourceBackendName cannot be null"
        );
        BackendType nonNullTargetType = Objects.requireNonNull(
                targetBackendType,
                "targetBackendType cannot be null"
        );
        Set<String> nonNullInitialExclusions =
                Set.copyOf(
                        Objects.requireNonNull(
                                initialExcludedServerNames,
                                "initialExcludedServerNames cannot be null"
                        )
                );

        Set<String> exclusions =
                new HashSet<>(nonNullInitialExclusions);
        boolean capacityRejected = false;

        while (true) {
            TransferTargetResolution resolution =
                    exclusions.isEmpty()
                            ? targetResolver.resolve(nonNullTargetType)
                            : targetResolver.resolve(
                                    nonNullTargetType,
                                    exclusions
                            );

            if (resolution.resolvedTarget().isEmpty()) {
                return PlayerTransferTargetAllocation.unavailable(
                        capacityRejected
                                ? TransferTargetResolution.noCapacity()
                                : resolution
                );
            }

            RegisteredServer target =
                    resolution.resolvedTarget().orElseThrow();

            String targetName =
                    target.getServerInfo().getName();

            if (exclusions.contains(targetName)) {
                throw new TransferTargetResolutionContractViolationException(
                        "resolver returned an excluded transfer target"
                );
            }

            if (nonNullSource.equals(targetName)) {
                return PlayerTransferTargetAllocation.sameTarget(
                        resolution
                );
            }

            PendingPlayerTransfer transfer =
                    new PendingPlayerTransfer(
                            nonNullRequestId,
                            nonNullPlayerId,
                            nonNullSource,
                            targetName,
                            requestedAt
                    );

            PlayerTransferRegistrationResult registrationResult =
                    transferRegistry.register(transfer);

            if (registrationResult
                    != PlayerTransferRegistrationResult.REGISTERED) {
                return PlayerTransferTargetAllocation
                        .registrationRejected(
                                resolution,
                                registrationResult
                        );
            }

            BackendCapacityReservation capacityReservation =
                    new BackendCapacityReservation(
                            nonNullRequestId,
                            nonNullPlayerId,
                            targetName
                    );

            final BackendCapacityReservationResult capacityResult;

            try {
                capacityResult = targetResolver.reserveCapacity(
                        capacityReservation,
                        target
                );
            } catch (RuntimeException exception) {
                transferRegistry.removeIfMatches(transfer);
                throw exception;
            }

            switch (capacityResult) {
                case RESERVED -> {
                    return PlayerTransferTargetAllocation.allocated(
                            resolution,
                            transfer,
                            capacityReservation
                    );
                }
                case NO_CAPACITY -> {
                    transferRegistry.removeIfMatches(transfer);
                    exclusions.add(targetName);
                    capacityRejected = true;
                }
                case ALREADY_RESERVED -> {
                    transferRegistry.removeIfMatches(transfer);
                    return PlayerTransferTargetAllocation
                            .registrationRejected(
                                    resolution,
                                    PlayerTransferRegistrationResult
                                            .ALREADY_REGISTERED
                            );
                }
                case REQUEST_ID_CONFLICT -> {
                    transferRegistry.removeIfMatches(transfer);
                    return PlayerTransferTargetAllocation
                            .registrationRejected(
                                    resolution,
                                    PlayerTransferRegistrationResult
                                            .REQUEST_ID_CONFLICT
                            );
                }
            }
        }
    }
}

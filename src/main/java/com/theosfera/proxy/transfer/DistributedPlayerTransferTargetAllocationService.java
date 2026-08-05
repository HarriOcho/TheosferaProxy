package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DistributedPlayerTransferTargetAllocationService {

    private final TransferTargetResolver targetResolver;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final PlayerSessionLeaseBindingRegistry sessionLeaseBindings;
    private final BackendCapacityCoordinator capacityCoordinator;
    private final BackendLoadSelector loadSelector;
    private final DistributedBackendLoadReader loadReader;

    public DistributedPlayerTransferTargetAllocationService(
            TransferTargetResolver targetResolver,
            PendingPlayerTransferRegistry transferRegistry,
            PlayerSessionLeaseBindingRegistry sessionLeaseBindings,
            BackendOccupancyCoordinator occupancyCoordinator,
            BackendCapacityCoordinator capacityCoordinator
    ) {
        this(
                targetResolver,
                transferRegistry,
                sessionLeaseBindings,
                capacityCoordinator,
                new BackendLoadSelector(),
                new DistributedBackendLoadReader(
                        occupancyCoordinator,
                        capacityCoordinator
                )
        );
    }

    DistributedPlayerTransferTargetAllocationService(
            TransferTargetResolver targetResolver,
            PendingPlayerTransferRegistry transferRegistry,
            PlayerSessionLeaseBindingRegistry sessionLeaseBindings,
            BackendCapacityCoordinator capacityCoordinator,
            BackendLoadSelector loadSelector,
            DistributedBackendLoadReader loadReader
    ) {
        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
        );
        this.transferRegistry = Objects.requireNonNull(
                transferRegistry,
                "transferRegistry cannot be null"
        );
        this.sessionLeaseBindings = Objects.requireNonNull(
                sessionLeaseBindings,
                "sessionLeaseBindings cannot be null"
        );
        this.capacityCoordinator = Objects.requireNonNull(
                capacityCoordinator,
                "capacityCoordinator cannot be null"
        );
        this.loadSelector = Objects.requireNonNull(
                loadSelector,
                "loadSelector cannot be null"
        );
        this.loadReader = Objects.requireNonNull(
                loadReader,
                "loadReader cannot be null"
        );
    }

    public CompletionStage<DistributedPlayerTransferTargetAllocation> allocate(
            Player player,
            UUID requestId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt
    ) {
        return allocate(
                player,
                requestId,
                sourceBackendName,
                targetBackendType,
                requestedAt,
                Set.of()
        );
    }

    public CompletionStage<DistributedPlayerTransferTargetAllocation> allocate(
            Player player,
            UUID requestId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt,
            Set<String> initialExcludedServerNames
    ) {
        Player nonNullPlayer = Objects.requireNonNull(
                player,
                "player cannot be null"
        );
        UUID nonNullRequestId = Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );
        String nonNullSource = Objects.requireNonNull(
                sourceBackendName,
                "sourceBackendName cannot be null"
        );
        BackendType nonNullTargetType = Objects.requireNonNull(
                targetBackendType,
                "targetBackendType cannot be null"
        );
        Set<String> exclusions = new HashSet<>(
                Set.copyOf(
                        Objects.requireNonNull(
                                initialExcludedServerNames,
                                "initialExcludedServerNames cannot be null"
                        )
                )
        );

        return allocateAttempt(
                nonNullPlayer,
                nonNullRequestId,
                nonNullSource,
                nonNullTargetType,
                requestedAt,
                exclusions,
                false
        );
    }

    private CompletionStage<DistributedPlayerTransferTargetAllocation>
    allocateAttempt(
            Player player,
            UUID requestId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt,
            Set<String> exclusions,
            boolean capacityRejected
    ) {
        TransferTargetCandidates candidates = targetResolver.candidates(
                targetBackendType,
                Set.copyOf(exclusions)
        );

        if (!candidates.configured()) {
            return capacityRejected
                    ? noCapacity()
                    : completed(
                            DistributedPlayerTransferTargetAllocation
                                    .unavailable(
                                            TransferTargetResolution
                                                    .notConfigured()
                                    )
                    );
        }

        if (candidates.activeCandidates().isEmpty()) {
            if (!candidates.coldCandidates().isEmpty()) {
                return reserveColdTarget(
                        player,
                        requestId,
                        sourceBackendName,
                        targetBackendType,
                        requestedAt,
                        exclusions,
                        candidates.coldCandidates().getFirst()
                );
            }

            return capacityRejected
                    ? noCapacity()
                    : completed(
                            DistributedPlayerTransferTargetAllocation
                                    .unavailable(
                                            TransferTargetResolution
                                                    .notAuthenticated()
                                    )
                    );
        }

        return loadReader.read(candidates.activeCandidates())
                .thenCompose(loads -> {
                    if (!loads.isAvailable()) {
                        return completed(
                                DistributedPlayerTransferTargetAllocation
                                        .capacityRejected(
                                                TransferTargetResolution
                                                        .noCapacity(),
                                                loads.failureStatus()
                                        )
                        );
                    }

                    Optional<RegisteredServer> selectedServer =
                            loadSelector.select(loads.candidates());

                    if (selectedServer.isPresent()) {
                        BackendTargetCandidate selected = findSelectedCandidate(
                                candidates,
                                selectedServer.orElseThrow()
                        );
                        return reserveTarget(
                                player,
                                requestId,
                                sourceBackendName,
                                targetBackendType,
                                requestedAt,
                                exclusions,
                                selected,
                                TransferTargetResolution.resolved(
                                        selected.server()
                                )
                        );
                    }

                    if (!candidates.coldCandidates().isEmpty()) {
                        return reserveColdTarget(
                                player,
                                requestId,
                                sourceBackendName,
                                targetBackendType,
                                requestedAt,
                                exclusions,
                                candidates.coldCandidates().getFirst()
                        );
                    }

                    return noCapacity();
                });
    }

    private CompletionStage<DistributedPlayerTransferTargetAllocation>
    reserveColdTarget(
            Player player,
            UUID requestId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt,
            Set<String> exclusions,
            BackendTargetCandidate coldTarget
    ) {
        return reserveTarget(
                player,
                requestId,
                sourceBackendName,
                targetBackendType,
                requestedAt,
                exclusions,
                coldTarget,
                TransferTargetResolution.bootstrapRequired(
                        coldTarget.server()
                )
        );
    }

    private BackendTargetCandidate findSelectedCandidate(
            TransferTargetCandidates candidates,
            RegisteredServer selectedServer
    ) {
        String selectedName = selectedServer
                .getServerInfo()
                .getName();

        return candidates.activeCandidates()
                .stream()
                .filter(candidate ->
                        candidate.serverName().equals(selectedName)
                )
                .findFirst()
                .orElseThrow(() ->
                        new TransferTargetResolutionContractViolationException(
                                "load selector returned an unknown candidate"
                        ));
    }

    private CompletionStage<DistributedPlayerTransferTargetAllocation>
    reserveTarget(
            Player player,
            UUID requestId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt,
            Set<String> exclusions,
            BackendTargetCandidate target,
            TransferTargetResolution resolution
    ) {
        if (exclusions.contains(target.serverName())) {
            return CompletableFuture.failedFuture(
                    new TransferTargetResolutionContractViolationException(
                            "resolver returned an excluded transfer target"
                    )
            );
        }

        if (sourceBackendName.equals(target.serverName())) {
            return completed(
                    DistributedPlayerTransferTargetAllocation.sameTarget(
                            resolution
                    )
            );
        }

        Optional<PlayerSessionLease> lease = sessionLeaseBindings.find(player);
        if (lease.isEmpty()) {
            return completed(
                    DistributedPlayerTransferTargetAllocation
                            .capacityRejected(
                                    resolution,
                                    BackendCapacityReserveResult.Status
                                            .SESSION_NOT_FOUND
                            )
            );
        }

        PendingPlayerTransfer transfer = new PendingPlayerTransfer(
                requestId,
                player.getUniqueId(),
                sourceBackendName,
                target.serverName(),
                requestedAt
        );
        PlayerTransferRegistrationResult registrationResult =
                transferRegistry.register(transfer);

        if (registrationResult
                != PlayerTransferRegistrationResult.REGISTERED) {
            return completed(
                    DistributedPlayerTransferTargetAllocation
                            .registrationRejected(
                                    resolution,
                                    registrationResult
                            )
            );
        }

        BackendCapacityReserveRequest capacityRequest =
                new BackendCapacityReserveRequest(
                        new BackendCapacityReservation(
                                requestId,
                                player.getUniqueId(),
                                target.serverName()
                        ),
                        lease.orElseThrow()
                );

        final CompletionStage<BackendCapacityReserveResult> reserveStage;
        try {
            reserveStage = capacityCoordinator.reserve(
                    capacityRequest,
                    target.policyEntry().capacity()
            );
        } catch (RuntimeException exception) {
            transferRegistry.removeIfMatches(transfer);
            return CompletableFuture.failedFuture(exception);
        }

        return reserveStage
                .handle((result, failure) -> {
                    if (failure != null || result == null) {
                        transferRegistry.removeIfMatches(transfer);
                        return completed(
                                DistributedPlayerTransferTargetAllocation
                                        .capacityRejected(
                                                resolution,
                                                BackendCapacityReserveResult
                                                        .Status
                                                        .COORDINATION_UNAVAILABLE
                                        )
                        );
                    }

                    return handleCapacityResult(
                            player,
                            requestId,
                            sourceBackendName,
                            targetBackendType,
                            requestedAt,
                            exclusions,
                            target,
                            resolution,
                            transfer,
                            capacityRequest,
                            result
                    );
                })
                .thenCompose(stage -> stage);
    }

    private CompletionStage<DistributedPlayerTransferTargetAllocation>
    handleCapacityResult(
            Player player,
            UUID requestId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt,
            Set<String> exclusions,
            BackendTargetCandidate target,
            TransferTargetResolution resolution,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendCapacityReserveResult result
    ) {
        return switch (result.status()) {
            case RESERVED, ALREADY_RESERVED -> successfulReservation(
                    resolution,
                    transfer,
                    capacityRequest,
                    result
            );
            case NO_CAPACITY -> {
                transferRegistry.removeIfMatches(transfer);
                Set<String> nextExclusions = new HashSet<>(exclusions);
                nextExclusions.add(target.serverName());
                yield allocateAttempt(
                        player,
                        requestId,
                        sourceBackendName,
                        targetBackendType,
                        requestedAt,
                        nextExclusions,
                        true
                );
            }
            case REQUEST_ID_CONFLICT,
                    SESSION_NOT_FOUND,
                    NOT_SESSION_OWNER,
                    OCCUPANCY_UNAVAILABLE,
                    COORDINATION_UNAVAILABLE -> {
                transferRegistry.removeIfMatches(transfer);
                yield completed(
                        DistributedPlayerTransferTargetAllocation
                                .capacityRejected(
                                        resolution,
                                        result.status()
                                )
                );
            }
        };
    }

    private CompletionStage<DistributedPlayerTransferTargetAllocation>
    successfulReservation(
            TransferTargetResolution resolution,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendCapacityReserveResult result
    ) {
        BackendCapacityReservation returnedReservation =
                result.reservedCapacity().orElseThrow();

        if (!returnedReservation.equals(capacityRequest.reservation())) {
            transferRegistry.removeIfMatches(transfer);
            return CompletableFuture.failedFuture(
                    new TransferTargetResolutionContractViolationException(
                            "capacity coordinator returned a different reservation"
                    )
            );
        }

        return completed(
                DistributedPlayerTransferTargetAllocation.allocated(
                        resolution,
                        transfer,
                        capacityRequest,
                        result.status()
                )
        );
    }

    private static CompletionStage<DistributedPlayerTransferTargetAllocation>
    noCapacity() {
        return completed(
                DistributedPlayerTransferTargetAllocation.capacityRejected(
                        TransferTargetResolution.noCapacity(),
                        BackendCapacityReserveResult.Status.NO_CAPACITY
                )
        );
    }

    private static CompletionStage<DistributedPlayerTransferTargetAllocation>
    completed(DistributedPlayerTransferTargetAllocation allocation) {
        return CompletableFuture.completedFuture(allocation);
    }
}

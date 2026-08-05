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

public final class DistributedResolvedTargetAllocationService {

    private final TransferTargetResolver targetResolver;
    private final PlayerSessionLeaseBindingRegistry sessionLeaseBindings;
    private final BackendCapacityCoordinator capacityCoordinator;
    private final BackendLoadSelector loadSelector;
    private final DistributedBackendLoadReader loadReader;

    public DistributedResolvedTargetAllocationService(
            TransferTargetResolver targetResolver,
            PlayerSessionLeaseBindingRegistry sessionLeaseBindings,
            BackendOccupancyCoordinator occupancyCoordinator,
            BackendCapacityCoordinator capacityCoordinator
    ) {
        this(
                targetResolver,
                sessionLeaseBindings,
                capacityCoordinator,
                new BackendLoadSelector(),
                new DistributedBackendLoadReader(
                        occupancyCoordinator,
                        capacityCoordinator
                )
        );
    }

    DistributedResolvedTargetAllocationService(
            TransferTargetResolver targetResolver,
            PlayerSessionLeaseBindingRegistry sessionLeaseBindings,
            BackendCapacityCoordinator capacityCoordinator,
            BackendLoadSelector loadSelector,
            DistributedBackendLoadReader loadReader
    ) {
        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
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

    public CompletionStage<DistributedResolvedTargetAllocation> allocate(
            Player player,
            UUID requestId,
            BackendType targetBackendType
    ) {
        return allocate(player, requestId, targetBackendType, Set.of());
    }

    public CompletionStage<DistributedResolvedTargetAllocation> allocate(
            Player player,
            UUID requestId,
            BackendType targetBackendType,
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

        if (nonNullTargetType == BackendType.AUTH) {
            return completed(
                    DistributedResolvedTargetAllocation.unavailable(
                            TransferTargetResolution.notConfigured()
                    )
            );
        }

        return allocateAttempt(
                nonNullPlayer,
                nonNullRequestId,
                nonNullTargetType,
                exclusions,
                false
        );
    }

    private CompletionStage<DistributedResolvedTargetAllocation>
    allocateAttempt(
            Player player,
            UUID requestId,
            BackendType targetBackendType,
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
                            DistributedResolvedTargetAllocation.unavailable(
                                    TransferTargetResolution.notConfigured()
                            )
                    );
        }

        if (candidates.activeCandidates().isEmpty()) {
            return capacityRejected
                    ? noCapacity()
                    : completed(
                            DistributedResolvedTargetAllocation.unavailable(
                                    TransferTargetResolution
                                            .notAuthenticated()
                            )
                    );
        }

        return loadReader.read(candidates.activeCandidates())
                .thenCompose(loads -> {
                    if (!loads.isAvailable()) {
                        return completed(
                                DistributedResolvedTargetAllocation
                                        .capacityRejected(
                                                TransferTargetResolution
                                                        .noCapacity(),
                                                loads.failureStatus()
                                        )
                        );
                    }

                    Optional<RegisteredServer> selectedServer =
                            loadSelector.select(loads.candidates());

                    if (selectedServer.isEmpty()) {
                        return noCapacity();
                    }

                    BackendTargetCandidate selected = findSelectedCandidate(
                            candidates,
                            selectedServer.orElseThrow()
                    );

                    return reserveTarget(
                            player,
                            requestId,
                            targetBackendType,
                            exclusions,
                            selected,
                            TransferTargetResolution.resolved(
                                    selected.server()
                            )
                    );
                });
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

    private CompletionStage<DistributedResolvedTargetAllocation>
    reserveTarget(
            Player player,
            UUID requestId,
            BackendType targetBackendType,
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

        Optional<PlayerSessionLease> lease = sessionLeaseBindings.find(player);
        if (lease.isEmpty()) {
            return completed(
                    DistributedResolvedTargetAllocation.capacityRejected(
                            resolution,
                            BackendCapacityReserveResult.Status
                                    .SESSION_NOT_FOUND
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
            return CompletableFuture.failedFuture(exception);
        }

        if (reserveStage == null) {
            return completed(
                    DistributedResolvedTargetAllocation.capacityRejected(
                            resolution,
                            BackendCapacityReserveResult.Status
                                    .COORDINATION_UNAVAILABLE
                    )
            );
        }

        return reserveStage
                .handle((result, failure) -> {
                    if (failure != null || result == null) {
                        return completed(
                                DistributedResolvedTargetAllocation
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
                            targetBackendType,
                            exclusions,
                            target,
                            resolution,
                            capacityRequest,
                            result
                    );
                })
                .thenCompose(stage -> stage);
    }

    private CompletionStage<DistributedResolvedTargetAllocation>
    handleCapacityResult(
            Player player,
            UUID requestId,
            BackendType targetBackendType,
            Set<String> exclusions,
            BackendTargetCandidate target,
            TransferTargetResolution resolution,
            BackendCapacityReserveRequest capacityRequest,
            BackendCapacityReserveResult result
    ) {
        return switch (result.status()) {
            case RESERVED, ALREADY_RESERVED -> successfulReservation(
                    resolution,
                    capacityRequest,
                    result
            );
            case NO_CAPACITY -> {
                Set<String> nextExclusions = new HashSet<>(exclusions);
                nextExclusions.add(target.serverName());
                yield allocateAttempt(
                        player,
                        requestId,
                        targetBackendType,
                        nextExclusions,
                        true
                );
            }
            case REQUEST_ID_CONFLICT,
                    SESSION_NOT_FOUND,
                    NOT_SESSION_OWNER,
                    OCCUPANCY_UNAVAILABLE,
                    COORDINATION_UNAVAILABLE -> completed(
                            DistributedResolvedTargetAllocation
                                    .capacityRejected(
                                            resolution,
                                            result.status()
                                    )
                    );
        };
    }

    private CompletionStage<DistributedResolvedTargetAllocation>
    successfulReservation(
            TransferTargetResolution resolution,
            BackendCapacityReserveRequest capacityRequest,
            BackendCapacityReserveResult result
    ) {
        BackendCapacityReservation returnedReservation =
                result.reservedCapacity().orElseThrow();

        if (!returnedReservation.equals(capacityRequest.reservation())) {
            return CompletableFuture.failedFuture(
                    new TransferTargetResolutionContractViolationException(
                            "capacity coordinator returned a different reservation"
                    )
            );
        }

        return completed(
                DistributedResolvedTargetAllocation.allocated(
                        resolution,
                        capacityRequest,
                        result.status()
                )
        );
    }

    private static CompletionStage<DistributedResolvedTargetAllocation>
    noCapacity() {
        return completed(
                DistributedResolvedTargetAllocation.capacityRejected(
                        TransferTargetResolution.noCapacity(),
                        BackendCapacityReserveResult.Status.NO_CAPACITY
                )
        );
    }

    private static CompletionStage<DistributedResolvedTargetAllocation>
    completed(DistributedResolvedTargetAllocation allocation) {
        return CompletableFuture.completedFuture(allocation);
    }
}

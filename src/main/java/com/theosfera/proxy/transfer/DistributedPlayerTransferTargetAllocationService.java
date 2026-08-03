package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.BackendOccupancyReadResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private final BackendOccupancyCoordinator occupancyCoordinator;
    private final BackendCapacityCoordinator capacityCoordinator;
    private final BackendLoadSelector loadSelector;

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
                occupancyCoordinator,
                capacityCoordinator,
                new BackendLoadSelector()
        );
    }

    DistributedPlayerTransferTargetAllocationService(
            TransferTargetResolver targetResolver,
            PendingPlayerTransferRegistry transferRegistry,
            PlayerSessionLeaseBindingRegistry sessionLeaseBindings,
            BackendOccupancyCoordinator occupancyCoordinator,
            BackendCapacityCoordinator capacityCoordinator,
            BackendLoadSelector loadSelector
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
        this.occupancyCoordinator = Objects.requireNonNull(
                occupancyCoordinator,
                "occupancyCoordinator cannot be null"
        );
        this.capacityCoordinator = Objects.requireNonNull(
                capacityCoordinator,
                "capacityCoordinator cannot be null"
        );
        this.loadSelector = Objects.requireNonNull(
                loadSelector,
                "loadSelector cannot be null"
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
            if (capacityRejected) {
                return completed(
                        DistributedPlayerTransferTargetAllocation
                                .capacityRejected(
                                        TransferTargetResolution.noCapacity(),
                                        BackendCapacityReserveResult.Status
                                                .NO_CAPACITY
                                )
                );
            }
            return completed(
                    DistributedPlayerTransferTargetAllocation.unavailable(
                            TransferTargetResolution.notConfigured()
                    )
            );
        }

        if (candidates.activeCandidates().isEmpty()) {
            if (!candidates.coldCandidates().isEmpty()) {
                BackendTargetCandidate coldTarget =
                        candidates.coldCandidates().getFirst();
                return reserveTarget(
                        player,
                        requestId,
                        sourceBackendName,
                        targetBackendType,
                        requestedAt,
                        exclusions,
                        capacityRejected,
                        coldTarget,
                        TransferTargetResolution.bootstrapRequired(
                                coldTarget.server()
                        )
                );
            }

            if (capacityRejected) {
                return completed(
                        DistributedPlayerTransferTargetAllocation
                                .capacityRejected(
                                        TransferTargetResolution.noCapacity(),
                                        BackendCapacityReserveResult.Status
                                                .NO_CAPACITY
                                )
                );
            }

            return completed(
                    DistributedPlayerTransferTargetAllocation.unavailable(
                            TransferTargetResolution.notAuthenticated()
                    )
            );
        }

        return readGlobalLoads(candidates.activeCandidates())
                .thenCompose(loads -> {
                    if (loads.failureStatus() != null) {
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
                        String selectedName = selectedServer
                                .orElseThrow()
                                .getServerInfo()
                                .getName();
                        BackendTargetCandidate selected =
                                candidates.activeCandidates()
                                        .stream()
                                        .filter(candidate ->
                                                candidate.serverName()
                                                        .equals(selectedName)
                                        )
                                        .findFirst()
                                        .orElseThrow(() ->
                                                new TransferTargetResolutionContractViolationException(
                                                        "load selector returned an unknown candidate"
                                                ));

                        return reserveTarget(
                                player,
                                requestId,
                                sourceBackendName,
                                targetBackendType,
                                requestedAt,
                                exclusions,
                                capacityRejected,
                                selected,
                                TransferTargetResolution.resolved(
                                        selected.server()
                                )
                        );
                    }

                    if (!candidates.coldCandidates().isEmpty()) {
                        BackendTargetCandidate coldTarget =
                                candidates.coldCandidates().getFirst();
                        return reserveTarget(
                                player,
                                requestId,
                                sourceBackendName,
                                targetBackendType,
                                requestedAt,
                                exclusions,
                                capacityRejected,
                                coldTarget,
                                TransferTargetResolution.bootstrapRequired(
                                        coldTarget.server()
                                )
                        );
                    }

                    return completed(
                            DistributedPlayerTransferTargetAllocation
                                    .capacityRejected(
                                            TransferTargetResolution
                                                    .noCapacity(),
                                            BackendCapacityReserveResult.Status
                                                    .NO_CAPACITY
                                    )
                    );
                });
    }

    private CompletionStage<DistributedPlayerTransferTargetAllocation>
    reserveTarget(
            Player player,
            UUID requestId,
            String sourceBackendName,
            BackendType targetBackendType,
            long requestedAt,
            Set<String> exclusions,
            boolean capacityRejected,
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

        Optional<PlayerSessionLease> lease =
                sessionLeaseBindings.find(player);

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

        BackendCapacityReservation reservation =
                new BackendCapacityReservation(
                        requestId,
                        player.getUniqueId(),
                        target.serverName()
                );
        BackendCapacityReserveRequest capacityRequest =
                new BackendCapacityReserveRequest(
                        reservation,
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
                            capacityRejected,
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
            boolean capacityRejected,
            BackendTargetCandidate target,
            TransferTargetResolution resolution,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendCapacityReserveResult result
    ) {
        return switch (result.status()) {
            case RESERVED, ALREADY_RESERVED -> {
                BackendCapacityReservation returnedReservation =
                        result.reservedCapacity().orElseThrow();

                if (!returnedReservation.equals(
                        capacityRequest.reservation()
                )) {
                    transferRegistry.removeIfMatches(transfer);
                    yield CompletableFuture.failedFuture(
                            new TransferTargetResolutionContractViolationException(
                                    "capacity coordinator returned a different reservation"
                            )
                    );
                }

                yield completed(
                        DistributedPlayerTransferTargetAllocation.allocated(
                                resolution,
                                transfer,
                                capacityRequest,
                                result.status()
                        )
                );
            }
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

    private CompletionStage<GlobalLoadRead> readGlobalLoads(
            List<BackendTargetCandidate> candidates
    ) {
        List<CompletableFuture<CandidateLoadRead>> reads =
                candidates.stream()
                        .map(this::readGlobalLoad)
                        .map(CompletionStage::toCompletableFuture)
                        .toList();

        CompletableFuture<Void> allReads = CompletableFuture.allOf(
                reads.toArray(CompletableFuture[]::new)
        );

        return allReads.handle((ignored, failure) -> {
            if (failure != null) {
                return GlobalLoadRead.failed(
                        BackendCapacityReserveResult.Status
                                .COORDINATION_UNAVAILABLE
                );
            }

            List<BackendLoadCandidate> loaded = new ArrayList<>();
            for (CompletableFuture<CandidateLoadRead> future : reads) {
                CandidateLoadRead read = future.join();
                if (read.failureStatus() != null) {
                    return GlobalLoadRead.failed(read.failureStatus());
                }
                loaded.add(read.candidate());
            }

            return GlobalLoadRead.available(loaded);
        });
    }

    private CompletionStage<CandidateLoadRead> readGlobalLoad(
            BackendTargetCandidate candidate
    ) {
        return occupancyCoordinator.read(candidate.serverName())
                .thenCombine(
                        capacityCoordinator.reservedCount(
                                candidate.serverName()
                        ),
                        (occupancy, reservedPlayers) -> {
                            if (occupancy == null
                                    || reservedPlayers == null) {
                                return CandidateLoadRead.failed(
                                        BackendCapacityReserveResult.Status
                                                .COORDINATION_UNAVAILABLE
                                );
                            }

                            if (occupancy.status()
                                    == BackendOccupancyReadResult.Status
                                    .COORDINATION_UNAVAILABLE) {
                                return CandidateLoadRead.failed(
                                        BackendCapacityReserveResult.Status
                                                .COORDINATION_UNAVAILABLE
                                );
                            }

                            if (occupancy.status()
                                    == BackendOccupancyReadResult.Status
                                    .BACKEND_NOT_FOUND) {
                                return CandidateLoadRead.failed(
                                        BackendCapacityReserveResult.Status
                                                .OCCUPANCY_UNAVAILABLE
                                );
                            }

                            int connectedPlayers = occupancy
                                    .occupancy()
                                    .orElseThrow();

                            return CandidateLoadRead.available(
                                    new BackendLoadCandidate(
                                            candidate.serverName(),
                                            candidate.server(),
                                            candidate.policyEntry(),
                                            connectedPlayers,
                                            reservedPlayers
                                    )
                            );
                        }
                );
    }

    private static CompletionStage<DistributedPlayerTransferTargetAllocation>
    completed(DistributedPlayerTransferTargetAllocation allocation) {
        return CompletableFuture.completedFuture(allocation);
    }

    private record CandidateLoadRead(
            BackendLoadCandidate candidate,
            BackendCapacityReserveResult.Status failureStatus
    ) {
        private static CandidateLoadRead available(
                BackendLoadCandidate candidate
        ) {
            return new CandidateLoadRead(
                    Objects.requireNonNull(candidate),
                    null
            );
        }

        private static CandidateLoadRead failed(
                BackendCapacityReserveResult.Status status
        ) {
            return new CandidateLoadRead(
                    null,
                    Objects.requireNonNull(status)
            );
        }
    }

    private record GlobalLoadRead(
            List<BackendLoadCandidate> candidates,
            BackendCapacityReserveResult.Status failureStatus
    ) {
        private GlobalLoadRead {
            candidates = List.copyOf(
                    Objects.requireNonNull(candidates)
            );
        }

        private static GlobalLoadRead available(
                List<BackendLoadCandidate> candidates
        ) {
            return new GlobalLoadRead(candidates, null);
        }

        private static GlobalLoadRead failed(
                BackendCapacityReserveResult.Status status
        ) {
            return new GlobalLoadRead(
                    List.of(),
                    Objects.requireNonNull(status)
            );
        }
    }
}

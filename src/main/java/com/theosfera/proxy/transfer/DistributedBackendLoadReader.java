package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.BackendOccupancyReadResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class DistributedBackendLoadReader {

    private final BackendOccupancyCoordinator occupancyCoordinator;
    private final BackendCapacityCoordinator capacityCoordinator;

    DistributedBackendLoadReader(
            BackendOccupancyCoordinator occupancyCoordinator,
            BackendCapacityCoordinator capacityCoordinator
    ) {
        this.occupancyCoordinator = Objects.requireNonNull(
                occupancyCoordinator,
                "occupancyCoordinator cannot be null"
        );
        this.capacityCoordinator = Objects.requireNonNull(
                capacityCoordinator,
                "capacityCoordinator cannot be null"
        );
    }

    CompletionStage<DistributedBackendLoadRead> read(
            List<BackendTargetCandidate> candidates
    ) {
        List<BackendTargetCandidate> snapshot = List.copyOf(
                Objects.requireNonNull(
                        candidates,
                        "candidates cannot be null"
                )
        );

        List<CompletableFuture<CandidateLoadRead>> reads = snapshot
                .stream()
                .map(this::readCandidate)
                .map(CompletionStage::toCompletableFuture)
                .toList();

        CompletableFuture<Void> allReads = CompletableFuture.allOf(
                reads.toArray(CompletableFuture[]::new)
        );

        return allReads.handle((ignored, failure) -> {
            if (failure != null) {
                return DistributedBackendLoadRead.failed(
                        BackendCapacityReserveResult.Status
                                .COORDINATION_UNAVAILABLE
                );
            }

            List<BackendLoadCandidate> loaded = new ArrayList<>();
            for (CompletableFuture<CandidateLoadRead> future : reads) {
                CandidateLoadRead read = future.join();
                if (read.failureStatus() != null) {
                    return DistributedBackendLoadRead.failed(
                            read.failureStatus()
                    );
                }
                loaded.add(read.candidate());
            }

            return DistributedBackendLoadRead.available(loaded);
        });
    }

    private CompletionStage<CandidateLoadRead> readCandidate(
            BackendTargetCandidate candidate
    ) {
        return occupancyCoordinator.read(candidate.serverName())
                .thenCombine(
                        capacityCoordinator.reservedCount(
                                candidate.serverName()
                        ),
                        (occupancy, reservedPlayers) -> toCandidateLoad(
                                candidate,
                                occupancy,
                                reservedPlayers
                        )
                );
    }

    private CandidateLoadRead toCandidateLoad(
            BackendTargetCandidate candidate,
            BackendOccupancyReadResult occupancy,
            Integer reservedPlayers
    ) {
        if (occupancy == null || reservedPlayers == null) {
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

        return CandidateLoadRead.available(
                new BackendLoadCandidate(
                        candidate.serverName(),
                        candidate.server(),
                        candidate.policyEntry(),
                        occupancy.occupancy().orElseThrow(),
                        reservedPlayers
                )
        );
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
}

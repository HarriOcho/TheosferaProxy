package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityReserveResult;

import java.util.List;
import java.util.Objects;

record DistributedBackendLoadRead(
        List<BackendLoadCandidate> candidates,
        BackendCapacityReserveResult.Status failureStatus
) {

    DistributedBackendLoadRead {
        candidates = List.copyOf(
                Objects.requireNonNull(
                        candidates,
                        "candidates cannot be null"
                )
        );

        if (failureStatus != null) {
            if (!candidates.isEmpty()) {
                throw new IllegalArgumentException(
                        "failed load read cannot contain candidates"
                );
            }

            if (failureStatus
                    != BackendCapacityReserveResult.Status
                    .OCCUPANCY_UNAVAILABLE
                    && failureStatus
                    != BackendCapacityReserveResult.Status
                    .COORDINATION_UNAVAILABLE) {
                throw new IllegalArgumentException(
                        "invalid distributed load failure status"
                );
            }
        }
    }

    static DistributedBackendLoadRead available(
            List<BackendLoadCandidate> candidates
    ) {
        return new DistributedBackendLoadRead(candidates, null);
    }

    static DistributedBackendLoadRead failed(
            BackendCapacityReserveResult.Status status
    ) {
        return new DistributedBackendLoadRead(
                List.of(),
                Objects.requireNonNull(status, "status cannot be null")
        );
    }

    boolean isAvailable() {
        return failureStatus == null;
    }
}

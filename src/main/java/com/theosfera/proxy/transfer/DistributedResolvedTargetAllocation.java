package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;

import java.util.Objects;

public record DistributedResolvedTargetAllocation(
        TransferTargetResolution targetResolution,
        BackendCapacityReserveRequest capacityRequest,
        BackendCapacityReserveResult.Status capacityStatus
) {

    public DistributedResolvedTargetAllocation {
        Objects.requireNonNull(
                targetResolution,
                "targetResolution cannot be null"
        );

        if (targetResolution.status()
                == TransferTargetResolutionStatus.BOOTSTRAP_REQUIRED) {
            throw new IllegalArgumentException(
                    "resolved target allocation cannot represent bootstrap"
            );
        }

        if (capacityRequest != null) {
            requireTarget(targetResolution);
            Objects.requireNonNull(
                    capacityStatus,
                    "capacityStatus cannot be null for allocated target"
            );

            if (capacityStatus
                    != BackendCapacityReserveResult.Status.RESERVED
                    && capacityStatus
                    != BackendCapacityReserveResult.Status.ALREADY_RESERVED) {
                throw new IllegalArgumentException(
                        "allocated target requires a successful capacity status"
                );
            }

            String targetName = targetResolution
                    .resolvedTarget()
                    .orElseThrow()
                    .getServerInfo()
                    .getName();

            if (!capacityRequest.reservation().backendName()
                    .equals(targetName)) {
                throw new IllegalArgumentException(
                        "capacity request must match resolved target"
                );
            }
        } else if (capacityStatus != null) {
            if (capacityStatus
                    == BackendCapacityReserveResult.Status.RESERVED
                    || capacityStatus
                    == BackendCapacityReserveResult.Status.ALREADY_RESERVED) {
                throw new IllegalArgumentException(
                        "successful capacity status requires allocation artifacts"
                );
            }
        } else if (targetResolution.resolvedTarget().isPresent()) {
            throw new IllegalArgumentException(
                    "target resolution requires an allocation outcome"
            );
        }
    }

    public static DistributedResolvedTargetAllocation unavailable(
            TransferTargetResolution resolution
    ) {
        return new DistributedResolvedTargetAllocation(
                resolution,
                null,
                null
        );
    }

    public static DistributedResolvedTargetAllocation capacityRejected(
            TransferTargetResolution resolution,
            BackendCapacityReserveResult.Status capacityStatus
    ) {
        BackendCapacityReserveResult.Status nonNullStatus =
                Objects.requireNonNull(
                        capacityStatus,
                        "capacityStatus cannot be null"
                );

        if (nonNullStatus
                == BackendCapacityReserveResult.Status.RESERVED
                || nonNullStatus
                == BackendCapacityReserveResult.Status.ALREADY_RESERVED) {
            throw new IllegalArgumentException(
                    "successful capacity status is not a rejection"
            );
        }

        return new DistributedResolvedTargetAllocation(
                resolution,
                null,
                nonNullStatus
        );
    }

    public static DistributedResolvedTargetAllocation allocated(
            TransferTargetResolution resolution,
            BackendCapacityReserveRequest capacityRequest,
            BackendCapacityReserveResult.Status capacityStatus
    ) {
        return new DistributedResolvedTargetAllocation(
                resolution,
                Objects.requireNonNull(
                        capacityRequest,
                        "capacityRequest cannot be null"
                ),
                capacityStatus
        );
    }

    public boolean isAllocated() {
        return capacityRequest != null;
    }

    public boolean isCapacityRejected() {
        return capacityStatus != null && !isAllocated();
    }

    public BackendCapacityReserveRequest requireCapacityRequest() {
        return Objects.requireNonNull(
                capacityRequest,
                "allocation has no capacity request"
        );
    }

    private static void requireTarget(
            TransferTargetResolution resolution
    ) {
        if (resolution.resolvedTarget().isEmpty()) {
            throw new IllegalArgumentException(
                    "allocation outcome requires a target"
            );
        }
    }
}

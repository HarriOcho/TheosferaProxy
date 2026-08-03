package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;

import java.util.Objects;

public record DistributedPlayerTransferTargetAllocation(
        TransferTargetResolution targetResolution,
        PendingPlayerTransfer transfer,
        BackendCapacityReserveRequest capacityRequest,
        PlayerTransferRegistrationResult registrationResult,
        BackendCapacityReserveResult.Status capacityStatus,
        boolean sameTarget
) {

    public DistributedPlayerTransferTargetAllocation {
        Objects.requireNonNull(
                targetResolution,
                "targetResolution cannot be null"
        );

        if (sameTarget) {
            requireTarget(targetResolution);
            requireNullArtifacts(
                    transfer,
                    capacityRequest,
                    registrationResult,
                    capacityStatus
            );
            return;
        }

        if (registrationResult != null) {
            if (registrationResult
                    == PlayerTransferRegistrationResult.REGISTERED) {
                throw new IllegalArgumentException(
                        "REGISTERED is not a rejected allocation"
                );
            }
            requireTarget(targetResolution);
            requireNullArtifacts(
                    transfer,
                    capacityRequest,
                    null,
                    capacityStatus
            );
            return;
        }

        if (transfer != null || capacityRequest != null) {
            requireTarget(targetResolution);
            Objects.requireNonNull(
                    transfer,
                    "transfer cannot be null for allocated target"
            );
            Objects.requireNonNull(
                    capacityRequest,
                    "capacityRequest cannot be null for allocated target"
            );
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

            BackendCapacityReservation reservation =
                    capacityRequest.reservation();

            if (!transfer.requestId().equals(reservation.requestId())
                    || !transfer.playerId().equals(reservation.playerId())
                    || !transfer.targetBackendName().equals(
                    reservation.backendName()
            )) {
                throw new IllegalArgumentException(
                        "capacity request must match transfer"
                );
            }
            return;
        }

        if (capacityStatus != null) {
            if (capacityStatus
                    == BackendCapacityReserveResult.Status.RESERVED
                    || capacityStatus
                    == BackendCapacityReserveResult.Status.ALREADY_RESERVED) {
                throw new IllegalArgumentException(
                        "successful capacity status requires allocation artifacts"
                );
            }
            return;
        }

        if (targetResolution.resolvedTarget().isPresent()) {
            throw new IllegalArgumentException(
                    "target resolution requires an allocation outcome"
            );
        }
    }

    public static DistributedPlayerTransferTargetAllocation unavailable(
            TransferTargetResolution resolution
    ) {
        return new DistributedPlayerTransferTargetAllocation(
                resolution,
                null,
                null,
                null,
                null,
                false
        );
    }

    public static DistributedPlayerTransferTargetAllocation sameTarget(
            TransferTargetResolution resolution
    ) {
        return new DistributedPlayerTransferTargetAllocation(
                resolution,
                null,
                null,
                null,
                null,
                true
        );
    }

    public static DistributedPlayerTransferTargetAllocation registrationRejected(
            TransferTargetResolution resolution,
            PlayerTransferRegistrationResult registrationResult
    ) {
        return new DistributedPlayerTransferTargetAllocation(
                resolution,
                null,
                null,
                Objects.requireNonNull(
                        registrationResult,
                        "registrationResult cannot be null"
                ),
                null,
                false
        );
    }

    public static DistributedPlayerTransferTargetAllocation capacityRejected(
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

        return new DistributedPlayerTransferTargetAllocation(
                resolution,
                null,
                null,
                null,
                nonNullStatus,
                false
        );
    }

    public static DistributedPlayerTransferTargetAllocation allocated(
            TransferTargetResolution resolution,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            BackendCapacityReserveResult.Status capacityStatus
    ) {
        return new DistributedPlayerTransferTargetAllocation(
                resolution,
                transfer,
                capacityRequest,
                null,
                capacityStatus,
                false
        );
    }

    public boolean isAllocated() {
        return transfer != null;
    }

    public boolean isSameTarget() {
        return sameTarget;
    }

    public boolean isRegistrationRejected() {
        return registrationResult != null;
    }

    public boolean isCapacityRejected() {
        return capacityStatus != null && !isAllocated();
    }

    public PendingPlayerTransfer requireTransfer() {
        return Objects.requireNonNull(
                transfer,
                "allocation has no transfer"
        );
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

    private static void requireNullArtifacts(
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            PlayerTransferRegistrationResult registrationResult,
            BackendCapacityReserveResult.Status capacityStatus
    ) {
        if (transfer != null
                || capacityRequest != null
                || registrationResult != null
                || capacityStatus != null) {
            throw new IllegalArgumentException(
                    "allocation artifacts must be absent"
            );
        }
    }
}

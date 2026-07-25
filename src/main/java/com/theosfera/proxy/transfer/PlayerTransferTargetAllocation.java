package com.theosfera.proxy.transfer;

import java.util.Objects;

public record PlayerTransferTargetAllocation(
        TransferTargetResolution targetResolution,
        PendingPlayerTransfer transfer,
        BackendCapacityReservation capacityReservation,
        PlayerTransferRegistrationResult registrationResult,
        boolean sameTarget
) {

    public PlayerTransferTargetAllocation {
        Objects.requireNonNull(
                targetResolution,
                "targetResolution cannot be null"
        );

        if (sameTarget) {
            requireTarget(targetResolution);
            requireNullArtifacts(
                    transfer,
                    capacityReservation,
                    registrationResult
            );
        } else if (registrationResult != null) {
            if (registrationResult
                    == PlayerTransferRegistrationResult.REGISTERED) {
                throw new IllegalArgumentException(
                        "REGISTERED is not a rejected allocation"
                );
            }

            requireTarget(targetResolution);
            requireNullArtifacts(
                    transfer,
                    capacityReservation,
                    null
            );
        } else if (transfer != null
                || capacityReservation != null) {
            requireTarget(targetResolution);
            Objects.requireNonNull(
                    transfer,
                    "transfer cannot be null for allocated target"
            );
            Objects.requireNonNull(
                    capacityReservation,
                    "capacityReservation cannot be null for allocated target"
            );

            if (!transfer.requestId().equals(
                    capacityReservation.requestId()
            ) || !transfer.playerId().equals(
                    capacityReservation.playerId()
            ) || !transfer.targetBackendName().equals(
                    capacityReservation.backendName()
            )) {
                throw new IllegalArgumentException(
                        "capacity reservation must match transfer"
                );
            }
        } else if (targetResolution
                .resolvedTarget()
                .isPresent()) {
            throw new IllegalArgumentException(
                    "target resolution requires an allocation outcome"
            );
        }
    }

    public static PlayerTransferTargetAllocation unavailable(
            TransferTargetResolution resolution
    ) {
        return new PlayerTransferTargetAllocation(
                resolution,
                null,
                null,
                null,
                false
        );
    }

    public static PlayerTransferTargetAllocation sameTarget(
            TransferTargetResolution resolution
    ) {
        return new PlayerTransferTargetAllocation(
                resolution,
                null,
                null,
                null,
                true
        );
    }

    public static PlayerTransferTargetAllocation registrationRejected(
            TransferTargetResolution resolution,
            PlayerTransferRegistrationResult registrationResult
    ) {
        return new PlayerTransferTargetAllocation(
                resolution,
                null,
                null,
                Objects.requireNonNull(
                        registrationResult,
                        "registrationResult cannot be null"
                ),
                false
        );
    }

    public static PlayerTransferTargetAllocation allocated(
            TransferTargetResolution resolution,
            PendingPlayerTransfer transfer,
            BackendCapacityReservation capacityReservation
    ) {
        return new PlayerTransferTargetAllocation(
                resolution,
                transfer,
                capacityReservation,
                null,
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

    public PendingPlayerTransfer requireTransfer() {
        return Objects.requireNonNull(
                transfer,
                "allocation has no transfer"
        );
    }

    public BackendCapacityReservation requireCapacityReservation() {
        return Objects.requireNonNull(
                capacityReservation,
                "allocation has no capacity reservation"
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
            BackendCapacityReservation capacityReservation,
            PlayerTransferRegistrationResult registrationResult
    ) {
        if (transfer != null
                || capacityReservation != null
                || registrationResult != null) {
            throw new IllegalArgumentException(
                    "allocation artifacts must be absent"
            );
        }
    }
}

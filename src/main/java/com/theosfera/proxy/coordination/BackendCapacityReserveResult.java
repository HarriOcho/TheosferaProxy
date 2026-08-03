package com.theosfera.proxy.coordination;

import com.theosfera.proxy.transfer.BackendCapacityReservation;

import java.util.Objects;
import java.util.Optional;

public record BackendCapacityReserveResult(
        Status status,
        BackendCapacityReservation reservation
) {

    public enum Status {
        RESERVED,
        ALREADY_RESERVED,
        REQUEST_ID_CONFLICT,
        NO_CAPACITY,
        OCCUPANCY_UNAVAILABLE,
        COORDINATION_UNAVAILABLE
    }

    public BackendCapacityReserveResult {
        Objects.requireNonNull(status, "status cannot be null");

        boolean requiresReservation = switch (status) {
            case RESERVED, ALREADY_RESERVED -> true;
            default -> false;
        };

        if (requiresReservation && reservation == null) {
            throw new IllegalArgumentException(
                    "successful capacity result requires reservation"
            );
        }
        if (!requiresReservation && reservation != null) {
            throw new IllegalArgumentException(
                    "unsuccessful capacity result cannot contain reservation"
            );
        }
    }

    public static BackendCapacityReserveResult withReservation(
            Status status,
            BackendCapacityReservation reservation
    ) {
        return new BackendCapacityReserveResult(status, reservation);
    }

    public static BackendCapacityReserveResult withoutReservation(
            Status status
    ) {
        return new BackendCapacityReserveResult(status, null);
    }

    public Optional<BackendCapacityReservation> reservedCapacity() {
        return Optional.ofNullable(reservation);
    }
}

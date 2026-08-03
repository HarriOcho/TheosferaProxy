package com.theosfera.proxy.coordination;

import com.theosfera.proxy.transfer.BackendCapacityReservation;

import java.util.Objects;

public record BackendCapacityReserveRequest(
        BackendCapacityReservation reservation,
        PlayerSessionLease sessionLease
) {

    public BackendCapacityReserveRequest {
        Objects.requireNonNull(
                reservation,
                "reservation cannot be null"
        );
        Objects.requireNonNull(
                sessionLease,
                "sessionLease cannot be null"
        );

        if (!reservation.playerId().equals(
                sessionLease.session().playerId()
        )) {
            throw new IllegalArgumentException(
                    "reservation playerId must match session lease"
            );
        }
    }
}

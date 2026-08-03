package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.OptionalInt;

public record BackendOccupancyReadResult(
        Status status,
        Integer connectedPlayers
) {

    public enum Status {
        AVAILABLE,
        BACKEND_NOT_FOUND,
        COORDINATION_UNAVAILABLE
    }

    public BackendOccupancyReadResult {
        Objects.requireNonNull(status, "status cannot be null");

        if (status == Status.AVAILABLE) {
            if (connectedPlayers == null) {
                throw new IllegalArgumentException(
                        "available occupancy requires connectedPlayers"
                );
            }
            if (connectedPlayers < 0) {
                throw new IllegalArgumentException(
                        "connectedPlayers cannot be negative"
                );
            }
        } else if (connectedPlayers != null) {
            throw new IllegalArgumentException(
                    "unavailable occupancy cannot contain connectedPlayers"
            );
        }
    }

    public static BackendOccupancyReadResult available(
            int connectedPlayers
    ) {
        return new BackendOccupancyReadResult(
                Status.AVAILABLE,
                connectedPlayers
        );
    }

    public static BackendOccupancyReadResult unavailable(
            Status status
    ) {
        if (status == Status.AVAILABLE) {
            throw new IllegalArgumentException(
                    "AVAILABLE requires connectedPlayers"
            );
        }
        return new BackendOccupancyReadResult(status, null);
    }

    public OptionalInt occupancy() {
        return connectedPlayers == null
                ? OptionalInt.empty()
                : OptionalInt.of(connectedPlayers);
    }
}

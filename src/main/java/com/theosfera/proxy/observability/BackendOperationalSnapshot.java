package com.theosfera.proxy.observability;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendHealthStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record BackendOperationalSnapshot(
        String serverName,
        BackendType backendType,
        int capacity,
        int preference,
        boolean registeredInVelocity,
        boolean authenticated,
        BackendHealthStatus healthStatus,
        Optional<Instant> lastHealthyActivity,
        int connectedPlayers,
        boolean bootstrapReservationPresent
) {

    public BackendOperationalSnapshot {
        serverName = requireServerName(serverName);

        Objects.requireNonNull(
                backendType,
                "backendType cannot be null"
        );

        Objects.requireNonNull(
                healthStatus,
                "healthStatus cannot be null"
        );

        lastHealthyActivity = Objects.requireNonNull(
                lastHealthyActivity,
                "lastHealthyActivity cannot be null"
        );

        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be greater than zero"
            );
        }

        if (preference < 0) {
            throw new IllegalArgumentException(
                    "preference cannot be negative"
            );
        }

        if (connectedPlayers < 0) {
            throw new IllegalArgumentException(
                    "connectedPlayers cannot be negative"
            );
        }
    }

    private static String requireServerName(String serverName) {
        String normalized = Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        ).trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "serverName cannot be blank"
            );
        }

        return normalized;
    }
}

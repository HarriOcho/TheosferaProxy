package com.theosfera.proxy.coordination;

import java.util.Objects;

public record PlayerPresencePublishRequest(
        PlayerSessionLease sessionLease,
        String backendName,
        long sequence,
        long observedAt
) {

    public PlayerPresencePublishRequest {
        Objects.requireNonNull(
                sessionLease,
                "sessionLease cannot be null"
        );
        backendName = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();

        if (backendName.isEmpty()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException(
                    "sequence must be greater than zero"
            );
        }
        if (observedAt <= 0) {
            throw new IllegalArgumentException(
                    "observedAt must be greater than zero"
            );
        }
    }

    public DistributedPlayerPresence presence() {
        return new DistributedPlayerPresence(
                sessionLease.session().playerId(),
                backendName,
                sessionLease.owner(),
                sessionLease.fencingToken(),
                sequence,
                observedAt
        );
    }
}

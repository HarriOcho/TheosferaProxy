package com.theosfera.proxy.coordination;

import java.util.Objects;

public record PlayerPresenceRemoveRequest(
        PlayerSessionLease sessionLease,
        String backendName,
        long sequence
) {

    public PlayerPresenceRemoveRequest {
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
    }
}

package com.theosfera.proxy.control;

import com.theosfera.proxy.backend.BackendIdentity;

import java.util.Objects;
import java.util.UUID;

public record BackendControlSession(
        UUID connectionId,
        BackendIdentity identity,
        long generation
) {

    public BackendControlSession {
        Objects.requireNonNull(
                connectionId,
                "connectionId cannot be null"
        );
        Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );

        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "generation must be positive"
            );
        }
    }
}

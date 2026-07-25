package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;

import java.util.Objects;

public record BackendPolicyEntry(
        BackendType backendType,
        int capacity,
        int preference
) {

    public BackendPolicyEntry {
        Objects.requireNonNull(
                backendType,
                "backendType cannot be null"
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
    }
}
package com.theosfera.proxy.backend;

import java.util.Objects;
import java.util.UUID;

public record PendingBackendPing(
        String serverName,
        UUID requestId,
        long sentAt
) {

    public PendingBackendPing {
        serverName = Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        ).trim();

        if (serverName.isEmpty()) {
            throw new IllegalArgumentException(
                    "serverName cannot be blank"
            );
        }

        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );

        if (sentAt <= 0) {
            throw new IllegalArgumentException(
                    "sentAt must be greater than zero"
            );
        }
    }
}

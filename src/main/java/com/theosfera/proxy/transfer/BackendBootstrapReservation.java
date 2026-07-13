package com.theosfera.proxy.transfer;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record BackendBootstrapReservation(
        String targetBackendName,
        UUID requestId,
        UUID playerId,
        long createdAt
) {

    private static final Pattern BACKEND_NAME_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
            );

    public BackendBootstrapReservation {
        targetBackendName = Objects.requireNonNull(
                targetBackendName,
                "targetBackendName cannot be null"
        ).trim();

        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );

        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        if (!BACKEND_NAME_PATTERN
                .matcher(targetBackendName)
                .matches()) {
            throw new IllegalArgumentException(
                    "targetBackendName must contain only letters, "
                            + "numbers, underscores or hyphens and "
                            + "contain at most 64 characters"
            );
        }

        if (createdAt <= 0L) {
            throw new IllegalArgumentException(
                    "createdAt must be greater than zero"
            );
        }
    }
}

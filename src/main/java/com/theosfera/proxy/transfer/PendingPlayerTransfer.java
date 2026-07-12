package com.theosfera.proxy.transfer;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PendingPlayerTransfer(
        UUID requestId,
        UUID playerId,
        String sourceBackendName,
        String targetBackendName,
        long requestedAt
) {

    private static final Pattern BACKEND_NAME_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
            );

    public PendingPlayerTransfer {
        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );

        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        sourceBackendName = validateBackendName(
                sourceBackendName,
                "sourceBackendName"
        );

        targetBackendName = validateBackendName(
                targetBackendName,
                "targetBackendName"
        );

        if (sourceBackendName.equals(targetBackendName)) {
            throw new IllegalArgumentException(
                    "sourceBackendName and targetBackendName "
                            + "cannot be equal"
            );
        }

        if (requestedAt <= 0) {
            throw new IllegalArgumentException(
                    "requestedAt must be greater than zero"
            );
        }
    }

    private static String validateBackendName(
            String backendName,
            String fieldName
    ) {
        String normalizedName = Objects.requireNonNull(
                backendName,
                fieldName + " cannot be null"
        ).trim();

        if (!BACKEND_NAME_PATTERN
                .matcher(normalizedName)
                .matches()) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must contain only letters, numbers, "
                            + "underscores or hyphens and contain "
                            + "at most 64 characters"
            );
        }

        return normalizedName;
    }
}
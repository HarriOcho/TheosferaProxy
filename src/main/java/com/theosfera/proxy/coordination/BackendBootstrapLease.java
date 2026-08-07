package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record BackendBootstrapLease(
        String targetBackendName,
        UUID requestId,
        UUID playerId,
        ProxyMembershipLease ownerMembership,
        long fencingToken
) {

    private static final Pattern BACKEND_NAME_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
            );

    public BackendBootstrapLease {
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

        Objects.requireNonNull(
                ownerMembership,
                "ownerMembership cannot be null"
        );

        if (!BACKEND_NAME_PATTERN
                .matcher(targetBackendName)
                .matches()) {
            throw new IllegalArgumentException(
                    "targetBackendName must be a valid backend name"
            );
        }

        if (fencingToken <= 0L) {
            throw new IllegalArgumentException(
                    "fencingToken must be greater than zero"
            );
        }
    }
}

package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record DistributedPlayerPresence(
        UUID playerId,
        String backendName,
        ProxyInstanceIdentity owner,
        long sessionFencingToken,
        long sequence,
        long observedAt
) {

    private static final Pattern BACKEND_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    public DistributedPlayerPresence {
        Objects.requireNonNull(playerId, "playerId cannot be null");
        backendName = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();
        Objects.requireNonNull(owner, "owner cannot be null");

        if (!BACKEND_NAME_PATTERN.matcher(backendName).matches()) {
            throw new IllegalArgumentException(
                    "backendName must contain only letters, numbers, "
                            + "underscores or hyphens and contain at most "
                            + "64 characters"
            );
        }
        if (sessionFencingToken <= 0) {
            throw new IllegalArgumentException(
                    "sessionFencingToken must be greater than zero"
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
}

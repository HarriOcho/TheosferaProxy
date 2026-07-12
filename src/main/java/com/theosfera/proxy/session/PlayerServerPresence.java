package com.theosfera.proxy.session;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PlayerServerPresence(
        UUID playerId,
        String backendName,
        long readyAt
) {

    private static final Pattern BACKEND_NAME_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
            );

    public PlayerServerPresence {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        backendName = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();

        if (!BACKEND_NAME_PATTERN.matcher(backendName).matches()) {
            throw new IllegalArgumentException(
                    "backendName must contain only letters, "
                            + "numbers, underscores or hyphens "
                            + "and contain at most 64 characters"
            );
        }

        if (readyAt <= 0) {
            throw new IllegalArgumentException(
                    "readyAt must be greater than zero"
            );
        }
    }
}
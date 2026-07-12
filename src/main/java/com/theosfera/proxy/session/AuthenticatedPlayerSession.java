package com.theosfera.proxy.session;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record AuthenticatedPlayerSession(
        UUID playerId,
        String playerName,
        long authenticatedAt
) {

    private static final Pattern PLAYER_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    public AuthenticatedPlayerSession {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        playerName = Objects.requireNonNull(
                playerName,
                "playerName cannot be null"
        ).trim();

        if (!PLAYER_NAME_PATTERN.matcher(playerName).matches()) {
            throw new IllegalArgumentException(
                    "playerName must contain between 3 and 16 "
                            + "letters, numbers or underscores"
            );
        }

        if (authenticatedAt <= 0) {
            throw new IllegalArgumentException(
                    "authenticatedAt must be greater than zero"
            );
        }
    }
}
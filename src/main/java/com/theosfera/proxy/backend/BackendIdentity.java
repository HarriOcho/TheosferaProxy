package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;

import java.util.Objects;
import java.util.regex.Pattern;

public record BackendIdentity(
        String serverName,
        BackendType backendType
) {

    private static final Pattern SERVER_NAME_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
            );

    public BackendIdentity {
        serverName = Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        ).trim();

        Objects.requireNonNull(
                backendType,
                "backendType cannot be null"
        );

        if (!SERVER_NAME_PATTERN.matcher(serverName).matches()) {
            throw new IllegalArgumentException(
                    "serverName must contain only letters, "
                            + "numbers, underscores or hyphens "
                            + "and contain at most 64 characters"
            );
        }
    }
}
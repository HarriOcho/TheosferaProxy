package com.theosfera.proxy.control;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record BackendControlConfig(
        boolean enabled,
        String bindHost,
        int bindPort,
        Duration authenticationTimeout,
        Path keyStorePath,
        String keyStorePasswordEnvironmentVariable,
        Path secretsFile
) {

    private static final Pattern ENVIRONMENT_VARIABLE_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public BackendControlConfig {
        bindHost = requireText(bindHost, "bindHost");

        if (bindPort < 1 || bindPort > 65_535) {
            throw new IllegalArgumentException(
                    "bindPort must be between 1 and 65535"
            );
        }

        authenticationTimeout = requirePositiveDuration(
                authenticationTimeout,
                "authenticationTimeout"
        );
        keyStorePath = Objects.requireNonNull(
                keyStorePath,
                "keyStorePath cannot be null"
        ).normalize();
        keyStorePasswordEnvironmentVariable = requireText(
                keyStorePasswordEnvironmentVariable,
                "keyStorePasswordEnvironmentVariable"
        );
        secretsFile = Objects.requireNonNull(
                secretsFile,
                "secretsFile cannot be null"
        ).normalize();

        if (!ENVIRONMENT_VARIABLE_PATTERN.matcher(
                keyStorePasswordEnvironmentVariable
        ).matches()) {
            throw new IllegalArgumentException(
                    "keyStorePasswordEnvironmentVariable must be a valid environment variable name"
            );
        }
    }

    public InetSocketAddress bindAddress() {
        return new InetSocketAddress(bindHost, bindPort);
    }

    private static String requireText(
            String value,
            String name
    ) {
        String normalized = Objects.requireNonNull(
                value,
                name + " cannot be null"
        ).trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " cannot be blank"
            );
        }

        return normalized;
    }

    private static Duration requirePositiveDuration(
            Duration value,
            String name
    ) {
        Duration nonNullValue = Objects.requireNonNull(
                value,
                name + " cannot be null"
        );

        if (nonNullValue.isZero()
                || nonNullValue.isNegative()
                || nonNullValue.toMillis() <= 0
                || nonNullValue.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    name + " must be positive and fit in Socket SO_TIMEOUT"
            );
        }

        return nonNullValue;
    }
}

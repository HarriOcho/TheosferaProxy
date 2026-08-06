package com.theosfera.proxy.control;

import com.theosfera.proxy.backend.BackendIdentity;

import java.util.Objects;
import java.util.Optional;

public record ControlAuthenticationResult(
        ControlAuthenticationStatus status,
        BackendIdentity identity
) {

    public ControlAuthenticationResult {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        if (status == ControlAuthenticationStatus.AUTHENTICATED
                && identity == null) {
            throw new IllegalArgumentException(
                    "authenticated result requires identity"
            );
        }

        if (status != ControlAuthenticationStatus.AUTHENTICATED
                && identity != null) {
            throw new IllegalArgumentException(
                    "rejected result cannot contain identity"
            );
        }
    }

    public static ControlAuthenticationResult authenticated(
            BackendIdentity identity
    ) {
        return new ControlAuthenticationResult(
                ControlAuthenticationStatus.AUTHENTICATED,
                Objects.requireNonNull(
                        identity,
                        "identity cannot be null"
                )
        );
    }

    public static ControlAuthenticationResult rejected(
            ControlAuthenticationStatus status
    ) {
        ControlAuthenticationStatus nonNullStatus =
                Objects.requireNonNull(
                        status,
                        "status cannot be null"
                );

        if (nonNullStatus == ControlAuthenticationStatus.AUTHENTICATED) {
            throw new IllegalArgumentException(
                    "rejected result cannot use AUTHENTICATED status"
            );
        }

        return new ControlAuthenticationResult(
                nonNullStatus,
                null
        );
    }

    public boolean accepted() {
        return status == ControlAuthenticationStatus.AUTHENTICATED;
    }

    public Optional<BackendIdentity> identityOptional() {
        return Optional.ofNullable(identity);
    }
}

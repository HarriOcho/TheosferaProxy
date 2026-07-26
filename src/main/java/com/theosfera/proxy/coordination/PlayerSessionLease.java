package com.theosfera.proxy.coordination;

import com.theosfera.proxy.session.AuthenticatedPlayerSession;

import java.util.Objects;

public record PlayerSessionLease(
        AuthenticatedPlayerSession session,
        ProxyInstanceIdentity owner,
        long fencingToken
) {

    public PlayerSessionLease {
        Objects.requireNonNull(
                session,
                "session cannot be null"
        );

        Objects.requireNonNull(
                owner,
                "owner cannot be null"
        );

        if (fencingToken <= 0) {
            throw new IllegalArgumentException(
                    "fencingToken must be greater than zero"
            );
        }
    }
}

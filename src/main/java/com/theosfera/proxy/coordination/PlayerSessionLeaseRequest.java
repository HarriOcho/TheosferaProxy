package com.theosfera.proxy.coordination;

import com.theosfera.proxy.session.AuthenticatedPlayerSession;

import java.util.Objects;

public record PlayerSessionLeaseRequest(
        AuthenticatedPlayerSession session,
        ProxyInstanceIdentity owner
) {

    public PlayerSessionLeaseRequest {
        Objects.requireNonNull(
                session,
                "session cannot be null"
        );

        Objects.requireNonNull(
                owner,
                "owner cannot be null"
        );
    }
}

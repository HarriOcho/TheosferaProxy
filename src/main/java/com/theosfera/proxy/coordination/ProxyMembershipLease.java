package com.theosfera.proxy.coordination;

import java.util.Objects;

public record ProxyMembershipLease(
        ProxyInstanceIdentity owner,
        long fencingToken
) {

    public ProxyMembershipLease {
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

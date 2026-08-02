package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.Optional;

public record ProxyMembershipAcquireResult(
        Status status,
        Optional<ProxyMembershipLease> lease
) {

    public ProxyMembershipAcquireResult {
        Objects.requireNonNull(status, "status cannot be null");
        lease = Objects.requireNonNull(lease, "lease cannot be null");

        boolean requiresLease = status == Status.ACQUIRED
                || status == Status.ALREADY_OWNED;

        if (requiresLease != lease.isPresent()) {
            throw new IllegalArgumentException(
                    "lease presence does not match acquire status"
            );
        }
    }

    public static ProxyMembershipAcquireResult acquired(
            ProxyMembershipLease lease
    ) {
        return withLease(Status.ACQUIRED, lease);
    }

    public static ProxyMembershipAcquireResult alreadyOwned(
            ProxyMembershipLease lease
    ) {
        return withLease(Status.ALREADY_OWNED, lease);
    }

    public static ProxyMembershipAcquireResult withoutLease(Status status) {
        return new ProxyMembershipAcquireResult(status, Optional.empty());
    }

    private static ProxyMembershipAcquireResult withLease(
            Status status,
            ProxyMembershipLease lease
    ) {
        return new ProxyMembershipAcquireResult(
                status,
                Optional.of(Objects.requireNonNull(lease, "lease cannot be null"))
        );
    }

    public enum Status {
        ACQUIRED,
        ALREADY_OWNED,
        OWNED_BY_OTHER_INCARNATION,
        COORDINATION_UNAVAILABLE
    }
}

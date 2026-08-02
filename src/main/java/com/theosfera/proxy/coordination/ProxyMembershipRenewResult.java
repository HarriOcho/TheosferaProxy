package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.Optional;

public record ProxyMembershipRenewResult(
        Status status,
        Optional<ProxyMembershipLease> lease
) {

    public ProxyMembershipRenewResult {
        Objects.requireNonNull(status, "status cannot be null");
        lease = Objects.requireNonNull(lease, "lease cannot be null");

        boolean requiresLease = status == Status.RENEWED;
        if (requiresLease != lease.isPresent()) {
            throw new IllegalArgumentException(
                    "lease presence does not match renew status"
            );
        }
    }

    public static ProxyMembershipRenewResult renewed(
            ProxyMembershipLease lease
    ) {
        return new ProxyMembershipRenewResult(
                Status.RENEWED,
                Optional.of(Objects.requireNonNull(lease, "lease cannot be null"))
        );
    }

    public static ProxyMembershipRenewResult withoutLease(Status status) {
        return new ProxyMembershipRenewResult(status, Optional.empty());
    }

    public enum Status {
        RENEWED,
        NOT_FOUND,
        NOT_OWNER,
        CONFLICT,
        COORDINATION_UNAVAILABLE
    }
}

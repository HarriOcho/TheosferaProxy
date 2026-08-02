package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.ProxyMembershipLease;

import java.util.Objects;
import java.util.Optional;

record RedisProxyMembershipAcquireResponse(
        RedisProxyMembershipAcquireStatus status,
        Optional<ProxyMembershipLease> lease
) {

    RedisProxyMembershipAcquireResponse {
        Objects.requireNonNull(status, "status cannot be null");
        lease = Objects.requireNonNull(lease, "lease cannot be null");

        boolean requiresLease = status == RedisProxyMembershipAcquireStatus.ACQUIRED
                || status == RedisProxyMembershipAcquireStatus.ALREADY_OWNED;
        if (requiresLease != lease.isPresent()) {
            throw new IllegalArgumentException(
                    "lease presence does not match acquire status"
            );
        }
    }

    static RedisProxyMembershipAcquireResponse withLease(
            RedisProxyMembershipAcquireStatus status,
            ProxyMembershipLease lease
    ) {
        return new RedisProxyMembershipAcquireResponse(
                status,
                Optional.of(Objects.requireNonNull(lease, "lease cannot be null"))
        );
    }

    static RedisProxyMembershipAcquireResponse withoutLease(
            RedisProxyMembershipAcquireStatus status
    ) {
        return new RedisProxyMembershipAcquireResponse(status, Optional.empty());
    }
}

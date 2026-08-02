package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.ProxyMembershipLease;

import java.util.Objects;
import java.util.Optional;

record RedisProxyMembershipRenewResponse(
        RedisProxyMembershipRenewStatus status,
        Optional<ProxyMembershipLease> lease
) {

    RedisProxyMembershipRenewResponse {
        Objects.requireNonNull(status, "status cannot be null");
        lease = Objects.requireNonNull(lease, "lease cannot be null");

        boolean requiresLease = status == RedisProxyMembershipRenewStatus.RENEWED;
        if (requiresLease != lease.isPresent()) {
            throw new IllegalArgumentException(
                    "lease presence does not match renew status"
            );
        }
    }

    static RedisProxyMembershipRenewResponse renewed(
            ProxyMembershipLease lease
    ) {
        return new RedisProxyMembershipRenewResponse(
                RedisProxyMembershipRenewStatus.RENEWED,
                Optional.of(Objects.requireNonNull(lease, "lease cannot be null"))
        );
    }

    static RedisProxyMembershipRenewResponse withoutLease(
            RedisProxyMembershipRenewStatus status
    ) {
        return new RedisProxyMembershipRenewResponse(status, Optional.empty());
    }
}

package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendBootstrapLease;

import java.util.Objects;
import java.util.Optional;

record RedisBackendBootstrapRenewResponse(
        RedisBackendBootstrapRenewStatus status,
        Optional<BackendBootstrapLease> lease
) {

    RedisBackendBootstrapRenewResponse {
        Objects.requireNonNull(status, "status cannot be null");
        lease = Objects.requireNonNull(lease, "lease cannot be null");

        boolean requiresLease =
                status == RedisBackendBootstrapRenewStatus.RENEWED;

        if (requiresLease != lease.isPresent()) {
            throw new IllegalArgumentException(
                    "lease presence does not match renew status"
            );
        }
    }

    static RedisBackendBootstrapRenewResponse renewed(
            BackendBootstrapLease lease
    ) {
        return new RedisBackendBootstrapRenewResponse(
                RedisBackendBootstrapRenewStatus.RENEWED,
                Optional.of(
                        Objects.requireNonNull(
                                lease,
                                "lease cannot be null"
                        )
                )
        );
    }

    static RedisBackendBootstrapRenewResponse withoutLease(
            RedisBackendBootstrapRenewStatus status
    ) {
        return new RedisBackendBootstrapRenewResponse(
                status,
                Optional.empty()
        );
    }
}

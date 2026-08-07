package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendBootstrapLease;

import java.util.Objects;
import java.util.Optional;

record RedisBackendBootstrapAcquireResponse(
        RedisBackendBootstrapAcquireStatus status,
        Optional<BackendBootstrapLease> lease
) {

    RedisBackendBootstrapAcquireResponse {
        Objects.requireNonNull(status, "status cannot be null");
        lease = Objects.requireNonNull(lease, "lease cannot be null");

        boolean requiresLease =
                status == RedisBackendBootstrapAcquireStatus.ACQUIRED
                        || status == RedisBackendBootstrapAcquireStatus
                        .ALREADY_OWNED;

        if (requiresLease != lease.isPresent()) {
            throw new IllegalArgumentException(
                    "lease presence does not match acquire status"
            );
        }
    }

    static RedisBackendBootstrapAcquireResponse withLease(
            RedisBackendBootstrapAcquireStatus status,
            BackendBootstrapLease lease
    ) {
        return new RedisBackendBootstrapAcquireResponse(
                status,
                Optional.of(
                        Objects.requireNonNull(
                                lease,
                                "lease cannot be null"
                        )
                )
        );
    }

    static RedisBackendBootstrapAcquireResponse withoutLease(
            RedisBackendBootstrapAcquireStatus status
    ) {
        return new RedisBackendBootstrapAcquireResponse(
                status,
                Optional.empty()
        );
    }
}

package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.PlayerSessionLease;

import java.util.Objects;
import java.util.Optional;

record RedisPlayerSessionRenewResponse(
        RedisPlayerSessionRenewStatus status,
        Optional<PlayerSessionLease> lease
) {

    RedisPlayerSessionRenewResponse {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        lease = Objects.requireNonNull(
                lease,
                "lease cannot be null"
        );
    }

    static RedisPlayerSessionRenewResponse renewed(
            PlayerSessionLease lease
    ) {
        return new RedisPlayerSessionRenewResponse(
                RedisPlayerSessionRenewStatus.RENEWED,
                Optional.of(
                        Objects.requireNonNull(
                                lease,
                                "lease cannot be null"
                        )
                )
        );
    }

    static RedisPlayerSessionRenewResponse withoutLease(
            RedisPlayerSessionRenewStatus status
    ) {
        return new RedisPlayerSessionRenewResponse(
                status,
                Optional.empty()
        );
    }
}

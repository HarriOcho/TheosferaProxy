package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.PlayerSessionLease;

import java.util.Objects;
import java.util.Optional;

record RedisPlayerSessionAcquireResponse(
        RedisPlayerSessionAcquireStatus status,
        Optional<PlayerSessionLease> lease
) {

    RedisPlayerSessionAcquireResponse {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        lease = Objects.requireNonNull(
                lease,
                "lease cannot be null"
        );
    }

    static RedisPlayerSessionAcquireResponse acquired(
            PlayerSessionLease lease
    ) {
        return withLease(
                RedisPlayerSessionAcquireStatus.ACQUIRED,
                lease
        );
    }

    static RedisPlayerSessionAcquireResponse alreadyOwned(
            PlayerSessionLease lease
    ) {
        return withLease(
                RedisPlayerSessionAcquireStatus.ALREADY_OWNED,
                lease
        );
    }

    static RedisPlayerSessionAcquireResponse withoutLease(
            RedisPlayerSessionAcquireStatus status
    ) {
        return new RedisPlayerSessionAcquireResponse(
                status,
                Optional.empty()
        );
    }

    private static RedisPlayerSessionAcquireResponse withLease(
            RedisPlayerSessionAcquireStatus status,
            PlayerSessionLease lease
    ) {
        return new RedisPlayerSessionAcquireResponse(
                status,
                Optional.of(
                        Objects.requireNonNull(
                                lease,
                                "lease cannot be null"
                        )
                )
        );
    }
}

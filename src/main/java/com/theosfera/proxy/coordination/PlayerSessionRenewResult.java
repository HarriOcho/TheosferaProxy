package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.Optional;

public record PlayerSessionRenewResult(
        Status status,
        Optional<PlayerSessionLease> lease
) {

    public PlayerSessionRenewResult {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        lease = Objects.requireNonNull(
                lease,
                "lease cannot be null"
        );

        boolean requiresLease = status == Status.RENEWED;

        if (requiresLease != lease.isPresent()) {
            throw new IllegalArgumentException(
                    "lease presence does not match renew status"
            );
        }
    }

    public static PlayerSessionRenewResult renewed(
            PlayerSessionLease lease
    ) {
        return new PlayerSessionRenewResult(
                Status.RENEWED,
                Optional.of(
                        Objects.requireNonNull(
                                lease,
                                "lease cannot be null"
                        )
                )
        );
    }

    public static PlayerSessionRenewResult withoutLease(
            Status status
    ) {
        return new PlayerSessionRenewResult(
                status,
                Optional.empty()
        );
    }

    public enum Status {
        RENEWED,
        NOT_FOUND,
        NOT_OWNER,
        CONFLICT,
        COORDINATION_UNAVAILABLE
    }
}

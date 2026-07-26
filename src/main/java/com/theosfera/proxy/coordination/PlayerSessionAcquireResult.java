package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.Optional;

public record PlayerSessionAcquireResult(
        Status status,
        Optional<PlayerSessionLease> lease
) {

    public PlayerSessionAcquireResult {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        lease = Objects.requireNonNull(
                lease,
                "lease cannot be null"
        );

        boolean requiresLease =
                status == Status.ACQUIRED
                        || status == Status.ALREADY_OWNED;

        if (requiresLease != lease.isPresent()) {
            throw new IllegalArgumentException(
                    "lease presence does not match acquire status"
            );
        }
    }

    public static PlayerSessionAcquireResult acquired(
            PlayerSessionLease lease
    ) {
        return withLease(Status.ACQUIRED, lease);
    }

    public static PlayerSessionAcquireResult alreadyOwned(
            PlayerSessionLease lease
    ) {
        return withLease(Status.ALREADY_OWNED, lease);
    }

    public static PlayerSessionAcquireResult withoutLease(
            Status status
    ) {
        return new PlayerSessionAcquireResult(
                status,
                Optional.empty()
        );
    }

    private static PlayerSessionAcquireResult withLease(
            Status status,
            PlayerSessionLease lease
    ) {
        return new PlayerSessionAcquireResult(
                status,
                Optional.of(
                        Objects.requireNonNull(
                                lease,
                                "lease cannot be null"
                        )
                )
        );
    }

    public enum Status {
        ACQUIRED,
        ALREADY_OWNED,
        OWNED_BY_OTHER_PROXY,
        CONFLICT,
        COORDINATION_UNAVAILABLE
    }
}

package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.Optional;

public record BackendBootstrapRenewResult(
        Status status,
        BackendBootstrapLease lease
) {

    public enum Status {
        RENEWED,
        NOT_FOUND,
        NOT_OWNER,
        CONFLICT,
        MEMBERSHIP_NOT_FOUND,
        NOT_MEMBERSHIP_OWNER,
        COORDINATION_UNAVAILABLE
    }

    public BackendBootstrapRenewResult {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        boolean requiresLease =
                status == Status.RENEWED;

        if (requiresLease && lease == null) {
            throw new IllegalArgumentException(
                    "successful bootstrap renew requires lease"
            );
        }

        if (!requiresLease && lease != null) {
            throw new IllegalArgumentException(
                    "failed bootstrap renew cannot contain lease"
            );
        }
    }

    public static BackendBootstrapRenewResult renewed(
            BackendBootstrapLease lease
    ) {
        return new BackendBootstrapRenewResult(
                Status.RENEWED,
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                )
        );
    }

    public static BackendBootstrapRenewResult withoutLease(
            Status status
    ) {
        return new BackendBootstrapRenewResult(
                status,
                null
        );
    }

    public Optional<BackendBootstrapLease> renewedLease() {
        return Optional.ofNullable(lease);
    }
}

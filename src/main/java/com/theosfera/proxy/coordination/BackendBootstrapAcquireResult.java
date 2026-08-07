package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.Optional;

public record BackendBootstrapAcquireResult(
        Status status,
        BackendBootstrapLease lease
) {

    public enum Status {
        ACQUIRED,
        ALREADY_OWNED,
        TARGET_BUSY,
        REQUEST_ID_CONFLICT,
        MEMBERSHIP_NOT_FOUND,
        NOT_MEMBERSHIP_OWNER,
        COORDINATION_UNAVAILABLE
    }

    public BackendBootstrapAcquireResult {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        boolean requiresLease = switch (status) {
            case ACQUIRED, ALREADY_OWNED -> true;
            default -> false;
        };

        if (requiresLease && lease == null) {
            throw new IllegalArgumentException(
                    "successful bootstrap acquire requires lease"
            );
        }

        if (!requiresLease && lease != null) {
            throw new IllegalArgumentException(
                    "failed bootstrap acquire cannot contain lease"
            );
        }
    }

    public static BackendBootstrapAcquireResult withLease(
            Status status,
            BackendBootstrapLease lease
    ) {
        return new BackendBootstrapAcquireResult(
                status,
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                )
        );
    }

    public static BackendBootstrapAcquireResult withoutLease(
            Status status
    ) {
        return new BackendBootstrapAcquireResult(
                status,
                null
        );
    }

    public Optional<BackendBootstrapLease> acquiredLease() {
        return Optional.ofNullable(lease);
    }
}

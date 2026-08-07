package com.theosfera.proxy.orchestration;

import java.util.Objects;

public record BackendStartResult(
        Status status
) {

    public enum Status {
        ACCEPTED,
        STALE_AUTHORITY,
        CONFLICT,
        TARGET_NOT_FOUND,
        PROVIDER_UNAVAILABLE,
        REJECTED
    }

    public BackendStartResult {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );
    }

    public static BackendStartResult accepted() {
        return new BackendStartResult(Status.ACCEPTED);
    }

    public static BackendStartResult of(Status status) {
        return new BackendStartResult(status);
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }
}

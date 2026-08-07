package com.theosfera.proxy.orchestration;

import java.util.Objects;

public record BackendStartActuationResult(
        Status status
) {

    public enum Status {
        ACCEPTED,
        STALE_AUTHORITY,
        CONFLICT,
        ACTUATOR_UNAVAILABLE,
        REJECTED
    }

    public BackendStartActuationResult {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );
    }

    public static BackendStartActuationResult accepted() {
        return new BackendStartActuationResult(Status.ACCEPTED);
    }

    public static BackendStartActuationResult of(Status status) {
        return new BackendStartActuationResult(status);
    }
}

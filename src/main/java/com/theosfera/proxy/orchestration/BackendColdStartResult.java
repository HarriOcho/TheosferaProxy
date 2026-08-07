package com.theosfera.proxy.orchestration;

import java.util.Objects;

public record BackendColdStartResult(
        Status status,
        String targetBackendName
) {

    public enum Status {
        READY,
        TARGET_BUSY,
        REQUEST_ID_CONFLICT,
        COORDINATION_UNAVAILABLE,
        START_TIMED_OUT,
        READINESS_TIMED_OUT,
        FENCED,
        FAILED
    }

    public BackendColdStartResult {
        Objects.requireNonNull(status, "status cannot be null");
        targetBackendName = Objects.requireNonNull(
                targetBackendName,
                "targetBackendName cannot be null"
        ).trim();
        if (targetBackendName.isEmpty()) {
            throw new IllegalArgumentException(
                    "targetBackendName cannot be blank"
            );
        }
    }

    public static BackendColdStartResult of(
            Status status,
            String targetBackendName
    ) {
        return new BackendColdStartResult(status, targetBackendName);
    }

    public boolean isReady() {
        return status == Status.READY;
    }
}

package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapLease;

import java.util.Objects;

public record BackendStartActuationRequest(
        BackendStartTarget target,
        BackendBootstrapLease bootstrapLease
) {

    public BackendStartActuationRequest {
        Objects.requireNonNull(
                target,
                "target cannot be null"
        );
        Objects.requireNonNull(
                bootstrapLease,
                "bootstrapLease cannot be null"
        );

        if (!target.backendName().equals(
                bootstrapLease.targetBackendName()
        )) {
            throw new IllegalArgumentException(
                    "target backend must match bootstrap authority"
            );
        }
    }
}

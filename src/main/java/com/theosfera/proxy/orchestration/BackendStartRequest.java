package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapLease;

import java.util.Objects;

public record BackendStartRequest(
        BackendBootstrapLease bootstrapLease
) {

    public BackendStartRequest {
        Objects.requireNonNull(
                bootstrapLease,
                "bootstrapLease cannot be null"
        );
    }
}

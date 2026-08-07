package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendHealthStatus;

import java.util.Objects;
import java.util.Optional;

public record BackendReadinessSnapshot(
        BackendReadinessStatus status,
        BackendIdentity identity,
        BackendHealthStatus healthStatus
) {

    public BackendReadinessSnapshot {
        Objects.requireNonNull(status, "status cannot be null");

        if (status == BackendReadinessStatus.READY) {
            Objects.requireNonNull(identity, "ready snapshot requires identity");
            if (healthStatus != BackendHealthStatus.HEALTHY) {
                throw new IllegalArgumentException(
                        "ready snapshot requires HEALTHY status"
                );
            }
        }

        if (status == BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED
                && identity != null) {
            throw new IllegalArgumentException(
                    "control-not-authenticated snapshot cannot contain identity"
            );
        }
    }

    public static BackendReadinessSnapshot ready(
            BackendIdentity identity
    ) {
        return new BackendReadinessSnapshot(
                BackendReadinessStatus.READY,
                Objects.requireNonNull(identity, "identity cannot be null"),
                BackendHealthStatus.HEALTHY
        );
    }

    public static BackendReadinessSnapshot of(
            BackendReadinessStatus status,
            BackendIdentity identity,
            BackendHealthStatus healthStatus
    ) {
        return new BackendReadinessSnapshot(status, identity, healthStatus);
    }

    public boolean isReady() {
        return status == BackendReadinessStatus.READY;
    }

    public Optional<BackendIdentity> observedIdentity() {
        return Optional.ofNullable(identity);
    }

    public Optional<BackendHealthStatus> observedHealthStatus() {
        return Optional.ofNullable(healthStatus);
    }
}

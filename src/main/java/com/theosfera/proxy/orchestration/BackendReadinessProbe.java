package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityProvider;
import com.theosfera.proxy.backend.BackendPolicyEntry;

import java.util.Objects;
import java.util.Optional;

/**
 * Reads backend readiness from the existing authoritative control identity and
 * health registries. It never probes TCP/process state and never treats a
 * provider result as readiness evidence.
 */
public final class BackendReadinessProbe {

    private final BackendAuthorizationPolicy authorizationPolicy;
    private final BackendIdentityProvider identityProvider;
    private final BackendHealthRegistry healthRegistry;

    public BackendReadinessProbe(
            BackendAuthorizationPolicy authorizationPolicy,
            BackendIdentityProvider identityProvider,
            BackendHealthRegistry healthRegistry
    ) {
        this.authorizationPolicy = Objects.requireNonNull(
                authorizationPolicy,
                "authorizationPolicy cannot be null"
        );
        this.identityProvider = Objects.requireNonNull(
                identityProvider,
                "identityProvider cannot be null"
        );
        this.healthRegistry = Objects.requireNonNull(
                healthRegistry,
                "healthRegistry cannot be null"
        );
    }

    public BackendReadinessSnapshot check(String backendName) {
        String normalizedBackendName = requireBackendName(backendName);
        BackendPolicyEntry policyEntry = authorizationPolicy
                .backendEntries()
                .get(normalizedBackendName);

        if (policyEntry == null) {
            return BackendReadinessSnapshot.of(
                    BackendReadinessStatus.TARGET_NOT_CONFIGURED,
                    null,
                    null
            );
        }

        Optional<BackendIdentity> identity =
                identityProvider.find(normalizedBackendName);

        if (identity.isEmpty()) {
            return BackendReadinessSnapshot.of(
                    BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED,
                    null,
                    healthRegistry.status(normalizedBackendName)
            );
        }

        BackendIdentity observedIdentity = identity.orElseThrow();
        if (!normalizedBackendName.equals(observedIdentity.serverName())
                || policyEntry.backendType()
                != observedIdentity.backendType()) {
            return BackendReadinessSnapshot.of(
                    BackendReadinessStatus.IDENTITY_MISMATCH,
                    observedIdentity,
                    healthRegistry.status(normalizedBackendName)
            );
        }

        BackendHealthStatus healthStatus =
                healthRegistry.status(normalizedBackendName);
        if (healthStatus != BackendHealthStatus.HEALTHY) {
            return BackendReadinessSnapshot.of(
                    BackendReadinessStatus.HEALTH_NOT_READY,
                    observedIdentity,
                    healthStatus
            );
        }

        return BackendReadinessSnapshot.ready(observedIdentity);
    }

    private static String requireBackendName(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
        }
        return normalized;
    }
}

package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BackendAuthorizationPolicy {

    private final Map<String, BackendPolicyEntry> backendEntries;
    private final Set<String> authorizedBackendNames;

    public BackendAuthorizationPolicy(
            Map<String, BackendPolicyEntry> backendEntries
    ) {
        Objects.requireNonNull(
                backendEntries,
                "backendEntries cannot be null"
        );

        Map<String, BackendPolicyEntry> validated =
                new LinkedHashMap<>();

        backendEntries.forEach((serverName, entry) -> {
            Objects.requireNonNull(
                    entry,
                    "backend entry cannot be null"
            );

            BackendIdentity identity =
                    new BackendIdentity(
                            serverName,
                            entry.backendType()
                    );

            BackendPolicyEntry previous =
                    validated.putIfAbsent(
                            identity.serverName(),
                            entry
                    );

            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate authorized backend: "
                                + identity.serverName()
                );
            }
        });

        this.backendEntries = Map.copyOf(validated);
        this.authorizedBackendNames =
                Set.copyOf(this.backendEntries.keySet());
    }

    public Optional<BackendIdentity> authorize(
            String sourceServerName,
            BackendType backendType
    ) {
        String nonNullSourceServerName = Objects.requireNonNull(
                sourceServerName,
                "sourceServerName cannot be null"
        );
        BackendType nonNullBackendType = Objects.requireNonNull(
                backendType,
                "backendType cannot be null"
        );

        BackendPolicyEntry expectedEntry =
                backendEntries.get(nonNullSourceServerName);

        if (expectedEntry == null
                || expectedEntry.backendType()
                != nonNullBackendType) {
            return Optional.empty();
        }

        return Optional.of(
                new BackendIdentity(
                        nonNullSourceServerName,
                        expectedEntry.backendType()
                )
        );
    }

    public Map<String, BackendPolicyEntry> backendEntries() {
        return backendEntries;
    }

    public Set<String> authorizedBackendNames() {
        return authorizedBackendNames;
    }
}

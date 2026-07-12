package com.theosfera.proxy.backend;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class BackendIdentityRegistry {

    private final Map<String, BackendIdentity> identities =
            new ConcurrentHashMap<>();

    public BackendRegistrationResult register(
            BackendIdentity identity
    ) {
        BackendIdentity nonNullIdentity =
                Objects.requireNonNull(
                        identity,
                        "identity cannot be null"
                );

        AtomicReference<BackendRegistrationResult> result =
                new AtomicReference<>();

        identities.compute(
                nonNullIdentity.serverName(),
                (serverName, existing) -> {
                    if (existing == null) {
                        result.set(
                                BackendRegistrationResult.REGISTERED
                        );
                        return nonNullIdentity;
                    }

                    if (existing.equals(nonNullIdentity)) {
                        result.set(
                                BackendRegistrationResult
                                        .ALREADY_REGISTERED
                        );
                        return existing;
                    }

                    result.set(
                            BackendRegistrationResult.CONFLICT
                    );
                    return existing;
                }
        );

        return result.get();
    }

    public Optional<BackendIdentity> find(
            String serverName
    ) {
        Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        );

        return Optional.ofNullable(
                identities.get(serverName)
        );
    }

    public boolean isRegistered(String serverName) {
        Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        );

        return identities.containsKey(serverName);
    }

    public Map<String, BackendIdentity> snapshot() {
        return Map.copyOf(identities);
    }

    public void clear() {
        identities.clear();
    }
}
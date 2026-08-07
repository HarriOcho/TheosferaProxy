package com.theosfera.proxy.backend;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class MutableBackendIdentityProvider
        implements BackendIdentityProvider {

    private final Map<String, BackendIdentity> identities =
            new ConcurrentHashMap<>();

    public void register(BackendIdentity identity) {
        BackendIdentity nonNullIdentity = Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );
        identities.put(nonNullIdentity.serverName(), nonNullIdentity);
    }

    public void remove(String serverName) {
        identities.remove(
                Objects.requireNonNull(
                        serverName,
                        "serverName cannot be null"
                )
        );
    }

    @Override
    public Optional<BackendIdentity> find(String serverName) {
        return Optional.ofNullable(
                identities.get(
                        Objects.requireNonNull(
                                serverName,
                                "serverName cannot be null"
                        )
                )
        );
    }

    @Override
    public Map<String, BackendIdentity> snapshot() {
        return Map.copyOf(identities);
    }

    public void clear() {
        identities.clear();
    }
}

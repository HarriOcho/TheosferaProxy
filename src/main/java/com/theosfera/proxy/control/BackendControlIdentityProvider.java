package com.theosfera.proxy.control;

import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BackendControlIdentityProvider
        implements BackendIdentityProvider {

    private final BackendControlSessionRegistry sessionRegistry;

    public BackendControlIdentityProvider(
            BackendControlSessionRegistry sessionRegistry
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
    }

    @Override
    public Optional<BackendIdentity> find(String serverName) {
        return sessionRegistry
                .find(serverName)
                .map(BackendControlSession::identity);
    }

    @Override
    public Map<String, BackendIdentity> snapshot() {
        Map<String, BackendIdentity> identities =
                new LinkedHashMap<>();

        sessionRegistry
                .snapshot()
                .forEach((serverName, session) ->
                        identities.put(
                                serverName,
                                session.identity()
                        )
                );

        return Map.copyOf(identities);
    }
}

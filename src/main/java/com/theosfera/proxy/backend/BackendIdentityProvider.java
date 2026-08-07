package com.theosfera.proxy.backend;

import java.util.Map;
import java.util.Optional;

public interface BackendIdentityProvider {

    Optional<BackendIdentity> find(String serverName);

    Map<String, BackendIdentity> snapshot();

    default boolean isRegistered(String serverName) {
        return find(serverName).isPresent();
    }
}

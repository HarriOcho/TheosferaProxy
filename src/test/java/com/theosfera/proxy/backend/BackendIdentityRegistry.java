package com.theosfera.proxy.backend;

import java.util.Map;
import java.util.Optional;

/**
 * Test-only mutable identity fixture retained for legacy test setup names.
 *
 * <p>Production identity is provided exclusively by the authenticated backend
 * control session through {@code BackendControlIdentityProvider}. This class
 * exists only under {@code src/test} so routing, failover and messaging tests
 * can express authenticated backend fixtures without reintroducing a runtime
 * identity registry.</p>
 */
public final class BackendIdentityRegistry
        implements BackendIdentityProvider {

    private final MutableBackendIdentityProvider delegate =
            new MutableBackendIdentityProvider();

    public void register(BackendIdentity identity) {
        delegate.register(identity);
    }

    public void remove(String serverName) {
        delegate.remove(serverName);
    }

    @Override
    public Optional<BackendIdentity> find(String serverName) {
        return delegate.find(serverName);
    }

    @Override
    public Map<String, BackendIdentity> snapshot() {
        return delegate.snapshot();
    }

    public void clear() {
        delegate.clear();
    }
}

package com.theosfera.proxy.control;

import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Invalidates name-scoped health evidence whenever a new authenticated control
 * generation becomes current.
 *
 * <p>This prevents a replacement control session from inheriting a HEALTHY
 * timestamp or pending PING that belonged to the previous generation. The new
 * generation must complete its own correlated PING/PONG before it can become
 * ready.</p>
 */
public final class BackendControlGenerationResetListener
        implements Consumer<BackendIdentity> {

    private final BackendHealthRegistry healthRegistry;
    private final PendingBackendPingRegistry pendingPingRegistry;
    private final Consumer<BackendIdentity> delegate;

    public BackendControlGenerationResetListener(
            BackendHealthRegistry healthRegistry,
            PendingBackendPingRegistry pendingPingRegistry,
            Consumer<BackendIdentity> delegate
    ) {
        this.healthRegistry = Objects.requireNonNull(
                healthRegistry,
                "healthRegistry cannot be null"
        );
        this.pendingPingRegistry = Objects.requireNonNull(
                pendingPingRegistry,
                "pendingPingRegistry cannot be null"
        );
        this.delegate = Objects.requireNonNull(
                delegate,
                "delegate cannot be null"
        );
    }

    @Override
    public void accept(BackendIdentity identity) {
        BackendIdentity nonNullIdentity = Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );
        String backendName = nonNullIdentity.serverName();

        pendingPingRegistry.remove(backendName);
        healthRegistry.remove(backendName);
        delegate.accept(nonNullIdentity);
    }
}

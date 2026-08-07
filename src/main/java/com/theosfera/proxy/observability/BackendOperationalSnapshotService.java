package com.theosfera.proxy.observability;

import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityProvider;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapReservation;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BackendOperationalSnapshotService {

    private final ProxyServer proxyServer;
    private final BackendAuthorizationPolicy authorizationPolicy;
    private final BackendIdentityProvider identityProvider;
    private final BackendHealthRegistry healthRegistry;
    private final BackendBootstrapRegistry bootstrapRegistry;

    public BackendOperationalSnapshotService(
            ProxyServer proxyServer,
            BackendAuthorizationPolicy authorizationPolicy,
            BackendIdentityProvider identityProvider,
            BackendHealthRegistry healthRegistry,
            BackendBootstrapRegistry bootstrapRegistry
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
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
        this.bootstrapRegistry = Objects.requireNonNull(
                bootstrapRegistry,
                "bootstrapRegistry cannot be null"
        );
    }

    public List<BackendOperationalSnapshot> capture() {
        Map<String, BackendIdentity> identities =
                identityProvider.snapshot();

        Map<String, Instant> healthyActivity =
                healthRegistry.snapshot();

        Map<UUID, BackendBootstrapReservation>
                bootstrapReservations =
                bootstrapRegistry.snapshotByRequest();

        Set<String> bootstrapTargets =
                bootstrapTargets(bootstrapReservations);

        List<BackendOperationalSnapshot> snapshots =
                new ArrayList<>();

        authorizationPolicy
                .backendEntries()
                .entrySet()
                .stream()
                .sorted(
                        Comparator.comparing(
                                Map.Entry::getKey
                        )
                )
                .map(entry ->
                        captureBackend(
                                entry.getKey(),
                                entry.getValue(),
                                identities,
                                healthyActivity,
                                bootstrapTargets
                        )
                )
                .forEach(snapshots::add);

        return List.copyOf(snapshots);
    }

    private BackendOperationalSnapshot captureBackend(
            String serverName,
            BackendPolicyEntry policyEntry,
            Map<String, BackendIdentity> identities,
            Map<String, Instant> healthyActivity,
            Set<String> bootstrapTargets
    ) {
        Optional<RegisteredServer> registeredServer =
                proxyServer.getServer(serverName);

        BackendIdentity identity = identities.get(serverName);

        boolean authenticated =
                identity != null
                        && identity.serverName().equals(serverName)
                        && identity.backendType()
                        == policyEntry.backendType();

        int connectedPlayers =
                registeredServer
                        .map(server ->
                                server.getPlayersConnected().size()
                        )
                        .orElse(0);

        return new BackendOperationalSnapshot(
                serverName,
                policyEntry.backendType(),
                policyEntry.capacity(),
                policyEntry.preference(),
                registeredServer.isPresent(),
                authenticated,
                healthRegistry.status(serverName),
                Optional.ofNullable(
                        healthyActivity.get(serverName)
                ),
                connectedPlayers,
                bootstrapTargets.contains(serverName)
        );
    }

    private Set<String> bootstrapTargets(
            Map<UUID, BackendBootstrapReservation> reservations
    ) {
        Set<String> targets = new HashSet<>();

        reservations
                .values()
                .forEach(reservation ->
                        targets.add(
                                reservation.targetBackendName()
                        )
                );

        return Set.copyOf(targets);
    }
}

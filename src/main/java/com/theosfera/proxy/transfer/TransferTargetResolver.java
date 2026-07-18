package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class TransferTargetResolver {

    private final ProxyServer proxyServer;
    private final BackendAuthorizationPolicy authorizationPolicy;
    private final BackendIdentityRegistry identityRegistry;

    public TransferTargetResolver(
            ProxyServer proxyServer,
            BackendAuthorizationPolicy authorizationPolicy,
            BackendIdentityRegistry identityRegistry
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );

        this.authorizationPolicy = Objects.requireNonNull(
                authorizationPolicy,
                "authorizationPolicy cannot be null"
        );

        this.identityRegistry = Objects.requireNonNull(
                identityRegistry,
                "identityRegistry cannot be null"
        );
    }

    public TransferTargetResolution resolve(
            BackendType targetBackendType
    ) {
        BackendType nonNullTargetType =
                Objects.requireNonNull(
                        targetBackendType,
                        "targetBackendType cannot be null"
                );

        if (nonNullTargetType == BackendType.AUTH) {
            return TransferTargetResolution.notConfigured();
        }

        List<RegisteredServer> configuredTargets =
                configuredTargets(nonNullTargetType);

        if (configuredTargets.isEmpty()) {
            return TransferTargetResolution.notConfigured();
        }

        for (RegisteredServer server : configuredTargets) {
            if (isAuthenticatedTarget(
                    server,
                    nonNullTargetType
            )) {
                return TransferTargetResolution.resolved(
                        server
                );
            }
        }

        for (RegisteredServer server : configuredTargets) {
            if (isEligibleColdTarget(
                    server,
                    nonNullTargetType
            )) {
                return TransferTargetResolution
                        .bootstrapRequired(server);
            }
        }

        return TransferTargetResolution.notAuthenticated();
    }

    private List<RegisteredServer> configuredTargets(
            BackendType targetBackendType
    ) {
        return authorizationPolicy
                .allowedBackends()
                .entrySet()
                .stream()
                .filter(entry ->
                        entry.getValue()
                                == targetBackendType
                )
                .map(entry ->
                        proxyServer.getServer(entry.getKey())
                )
                .flatMap(optional -> optional.stream())
                .sorted(
                        Comparator.comparing(server ->
                                server.getServerInfo()
                                        .getName()
                        )
                )
                .toList();
    }

    private boolean isAuthenticatedTarget(
            RegisteredServer server,
            BackendType expectedType
    ) {
        String serverName =
                server.getServerInfo().getName();

        return identityRegistry
                .find(serverName)
                .filter(identity ->
                        matchesExpectedIdentity(
                                identity,
                                serverName,
                                expectedType
                        )
                )
                .isPresent()
                && hasConnectedPlayers(server);
    }

    private boolean isEligibleColdTarget(
            RegisteredServer server,
            BackendType expectedType
    ) {
        String serverName =
                server.getServerInfo().getName();

        if (hasConnectedPlayers(server)) {
            return false;
        }

        return identityRegistry
                .find(serverName)
                .map(identity ->
                        matchesExpectedIdentity(
                                identity,
                                serverName,
                                expectedType
                        )
                )
                .orElse(true);
    }

    private boolean hasConnectedPlayers(
            RegisteredServer server
    ) {
        return !server.getPlayersConnected().isEmpty();
    }

    private boolean matchesExpectedIdentity(
            BackendIdentity identity,
            String serverName,
            BackendType expectedType
    ) {
        return identity.serverName().equals(serverName)
                && identity.backendType() == expectedType;
    }
}

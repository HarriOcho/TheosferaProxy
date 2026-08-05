package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class TransferTargetResolver {

    private final ProxyServer proxyServer;
    private final BackendAuthorizationPolicy authorizationPolicy;
    private final BackendIdentityRegistry identityRegistry;
    private final BackendHealthRegistry healthRegistry;
    private final BackendLoadSelector loadSelector;

    public TransferTargetResolver(
            ProxyServer proxyServer,
            BackendAuthorizationPolicy authorizationPolicy,
            BackendIdentityRegistry identityRegistry,
            BackendHealthRegistry healthRegistry
    ) {
        this(
                proxyServer,
                authorizationPolicy,
                identityRegistry,
                healthRegistry,
                new BackendLoadSelector()
        );
    }

    TransferTargetResolver(
            ProxyServer proxyServer,
            BackendAuthorizationPolicy authorizationPolicy,
            BackendIdentityRegistry identityRegistry,
            BackendHealthRegistry healthRegistry,
            BackendLoadSelector loadSelector
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

        this.healthRegistry = Objects.requireNonNull(
                healthRegistry,
                "healthRegistry cannot be null"
        );

        this.loadSelector = Objects.requireNonNull(
                loadSelector,
                "loadSelector cannot be null"
        );
    }

    public TransferTargetResolution resolve(
            BackendType targetBackendType
    ) {
        return resolve(
                targetBackendType,
                Set.of()
        );
    }

    public TransferTargetResolution resolve(
            BackendType targetBackendType,
            Set<String> excludedServerNames
    ) {
        BackendType nonNullTargetType =
                Objects.requireNonNull(
                        targetBackendType,
                        "targetBackendType cannot be null"
                );

        Set<String> nonNullExcludedServerNames =
                Set.copyOf(
                        Objects.requireNonNull(
                                excludedServerNames,
                                "excludedServerNames cannot be null"
                        )
                );

        if (nonNullTargetType == BackendType.AUTH) {
            return TransferTargetResolution.notConfigured();
        }

        List<RegisteredServer> configuredTargets =
                configuredTargets(
                        nonNullTargetType,
                        nonNullExcludedServerNames
                );

        if (configuredTargets.isEmpty()) {
            return TransferTargetResolution.notConfigured();
        }

        List<BackendLoadCandidate> activeCandidates =
                authenticatedCandidates(
                        configuredTargets,
                        nonNullTargetType
                );

        Optional<RegisteredServer> balancedTarget =
                loadSelector.select(
                        activeCandidates
                );

        if (balancedTarget.isPresent()) {
            return TransferTargetResolution.resolved(
                    balancedTarget.orElseThrow()
            );
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

        if (!activeCandidates.isEmpty()) {
            return TransferTargetResolution.noCapacity();
        }

        return TransferTargetResolution.notAuthenticated();
    }

    public TransferTargetCandidates candidates(
            BackendType targetBackendType
    ) {
        return candidates(targetBackendType, Set.of());
    }

    public TransferTargetCandidates candidates(
            BackendType targetBackendType,
            Set<String> excludedServerNames
    ) {
        BackendType nonNullTargetType = Objects.requireNonNull(
                targetBackendType,
                "targetBackendType cannot be null"
        );
        Set<String> nonNullExcludedServerNames = Set.copyOf(
                Objects.requireNonNull(
                        excludedServerNames,
                        "excludedServerNames cannot be null"
                )
        );

        if (nonNullTargetType == BackendType.AUTH) {
            return TransferTargetCandidates.notConfigured();
        }

        List<RegisteredServer> configuredTargets = configuredTargets(
                nonNullTargetType,
                nonNullExcludedServerNames
        );

        if (configuredTargets.isEmpty()) {
            return TransferTargetCandidates.notConfigured();
        }

        List<BackendTargetCandidate> activeCandidates =
                configuredTargets.stream()
                        .filter(server -> isAuthenticatedDistributedTarget(
                                server,
                                nonNullTargetType
                        ))
                        .map(server -> targetCandidate(
                                server,
                                nonNullTargetType
                        ))
                        .toList();

        List<BackendTargetCandidate> coldCandidates =
                configuredTargets.stream()
                        .filter(server -> !isAuthenticatedDistributedTarget(
                                server,
                                nonNullTargetType
                        ))
                        .filter(server -> isEligibleColdTarget(
                                server,
                                nonNullTargetType
                        ))
                        .map(server -> targetCandidate(
                                server,
                                nonNullTargetType
                        ))
                        .toList();

        return TransferTargetCandidates.configured(
                activeCandidates,
                coldCandidates
        );
    }

    private List<RegisteredServer> configuredTargets(
            BackendType targetBackendType,
            Set<String> excludedServerNames
    ) {
        return authorizationPolicy
                .backendEntries()
                .entrySet()
                .stream()
                .filter(entry ->
                        entry.getValue()
                                .backendType()
                                == targetBackendType
                )
                .filter(entry ->
                        !excludedServerNames.contains(
                                entry.getKey()
                        )
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

    private List<BackendLoadCandidate> authenticatedCandidates(
            List<RegisteredServer> configuredTargets,
            BackendType expectedType
    ) {
        return configuredTargets
                .stream()
                .map(server ->
                        authenticatedCandidate(
                                server,
                                expectedType
                        )
                )
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<BackendLoadCandidate> authenticatedCandidate(
            RegisteredServer server,
            BackendType expectedType
    ) {
        if (!isAuthenticatedActiveTarget(server, expectedType)) {
            return Optional.empty();
        }

        String serverName = server.getServerInfo().getName();
        BackendPolicyEntry policyEntry = authorizationPolicy
                .backendEntries()
                .get(serverName);
        int connectedPlayers = server.getPlayersConnected().size();

        return Optional.of(
                new BackendLoadCandidate(
                        serverName,
                        server,
                        policyEntry,
                        connectedPlayers,
                        0
                )
        );
    }

    private boolean isAuthenticatedActiveTarget(
            RegisteredServer server,
            BackendType expectedType
    ) {
        return isAuthenticatedDistributedTarget(server, expectedType)
                && hasConnectedPlayers(server);
    }

    private boolean isAuthenticatedDistributedTarget(
            RegisteredServer server,
            BackendType expectedType
    ) {
        String serverName = server.getServerInfo().getName();
        BackendPolicyEntry policyEntry = authorizationPolicy
                .backendEntries()
                .get(serverName);

        if (policyEntry == null
                || policyEntry.backendType() != expectedType) {
            return false;
        }

        boolean authenticated = identityRegistry
                .find(serverName)
                .filter(identity -> matchesExpectedIdentity(
                        identity,
                        serverName,
                        expectedType
                ))
                .isPresent();

        return authenticated
                && healthRegistry.status(serverName)
                == BackendHealthStatus.HEALTHY;
    }

    private BackendTargetCandidate targetCandidate(
            RegisteredServer server,
            BackendType expectedType
    ) {
        String serverName = server.getServerInfo().getName();
        BackendPolicyEntry policyEntry = authorizationPolicy
                .backendEntries()
                .get(serverName);

        if (policyEntry == null
                || policyEntry.backendType() != expectedType) {
            throw new TransferTargetResolutionContractViolationException(
                    "candidate target does not match backend policy"
            );
        }

        return new BackendTargetCandidate(
                serverName,
                server,
                policyEntry
        );
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

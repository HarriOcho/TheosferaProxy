package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolutionStatus;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BackendKickFailoverService {

    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final BackendIdentityRegistry identityRegistry;
    private final TransferTargetResolver targetResolver;
    private final PendingPlayerFailoverRegistry failoverRegistry;

    public BackendKickFailoverService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            BackendIdentityRegistry identityRegistry,
            TransferTargetResolver targetResolver,
            PendingPlayerFailoverRegistry failoverRegistry
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );

        this.identityRegistry = Objects.requireNonNull(
                identityRegistry,
                "identityRegistry cannot be null"
        );

        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
        );

        this.failoverRegistry = Objects.requireNonNull(
                failoverRegistry,
                "failoverRegistry cannot be null"
        );
    }

    public Optional<RegisteredServer> resolveFailoverTarget(
            KickedFromServerEvent event
    ) {
        KickedFromServerEvent nonNullEvent =
                Objects.requireNonNull(
                        event,
                        "event cannot be null"
                );

        if (!nonNullEvent.kickedDuringServerConnect()) {
            return Optional.empty();
        }

        Player player =
                nonNullEvent.getPlayer();

        UUID playerId =
                player.getUniqueId();

        if (!sessionRegistry.isAuthenticated(playerId)) {
            return Optional.empty();
        }

        if (failoverRegistry.isReserved(playerId)) {
            return Optional.empty();
        }

        RegisteredServer failedServer =
                nonNullEvent.getServer();

        String failedServerName =
                failedServer.getServerInfo().getName();

        Optional<String> currentServerName =
                currentServerName(player);

        Optional<BackendIdentity> identityOptional =
                identityRegistry.find(failedServerName);

        if (identityOptional.isEmpty()) {
            return Optional.empty();
        }

        BackendIdentity identity =
                identityOptional.get();

        if (!identity.serverName().equals(failedServerName)) {
            return Optional.empty();
        }

        BackendType sourceType =
                identity.backendType();

        if (sourceType == BackendType.AUTH) {
            return Optional.empty();
        }

        Set<String> exclusions =
                Set.of(failedServerName);

        Optional<RegisteredServer> sameTypeTarget =
                resolvedTarget(
                        sourceType,
                        exclusions,
                        failedServerName,
                        currentServerName
                );

        if (sameTypeTarget.isPresent()) {
            return reserve(
                    playerId,
                    sameTypeTarget.get()
            );
        }

        if (sourceType == BackendType.LOBBY) {
            return Optional.empty();
        }

        Optional<RegisteredServer> lobbyTarget =
                resolvedTarget(
                        BackendType.LOBBY,
                        exclusions,
                        failedServerName,
                        currentServerName
                );

        if (lobbyTarget.isEmpty()) {
            return Optional.empty();
        }

        return reserve(
                playerId,
                lobbyTarget.get()
        );
    }

    public void clearPendingFailover(UUID playerId) {
        failoverRegistry.clear(playerId);
    }

    private Optional<RegisteredServer> resolvedTarget(
            BackendType targetType,
            Set<String> exclusions,
            String failedServerName,
            Optional<String> currentServerName
    ) {
        if (targetType == BackendType.AUTH) {
            return Optional.empty();
        }

        TransferTargetResolution resolution =
                targetResolver.resolve(
                        targetType,
                        exclusions
                );

        if (resolution.status()
                != TransferTargetResolutionStatus.RESOLVED) {
            return Optional.empty();
        }

        return resolution
                .resolvedTarget()
                .filter(target ->
                        isDifferentServer(
                                target,
                                failedServerName,
                                currentServerName
                        )
                );
    }

    private Optional<String> currentServerName(Player player) {
        return player
                .getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(serverInfo -> serverInfo.getName());
    }

    private boolean isDifferentServer(
            RegisteredServer target,
            String failedServerName,
            Optional<String> currentServerName
    ) {
        String targetName =
                target.getServerInfo().getName();

        if (targetName.equals(failedServerName)) {
            return false;
        }

        return currentServerName
                .map(currentName ->
                        !targetName.equals(currentName)
                )
                .orElse(true);
    }

    private Optional<RegisteredServer> reserve(
            UUID playerId,
            RegisteredServer target
    ) {
        if (!failoverRegistry.reserve(playerId)) {
            return Optional.empty();
        }

        return Optional.of(target);
    }
}

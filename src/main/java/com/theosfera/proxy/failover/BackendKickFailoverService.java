package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class BackendKickFailoverService {

    private static final Component NO_SAFE_TARGET_REASON =
            Component.text(
                    "No hay servidores seguros disponibles en este momento. "
                            + "Inténtalo nuevamente más tarde."
            );

    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final BackendIdentityRegistry identityRegistry;
    private final DistributedBackendKickFailoverCoordinator coordinator;

    public BackendKickFailoverService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            BackendIdentityRegistry identityRegistry,
            DistributedBackendKickFailoverCoordinator coordinator
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
        this.identityRegistry = Objects.requireNonNull(
                identityRegistry,
                "identityRegistry cannot be null"
        );
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator cannot be null"
        );
    }

    public CompletionStage<BackendKickFailoverResolution> resolveFailoverTarget(
            KickedFromServerEvent event
    ) {
        KickedFromServerEvent nonNullEvent = Objects.requireNonNull(
                event,
                "event cannot be null"
        );

        if (nonNullEvent.kickedDuringServerConnect()) {
            return completed(BackendKickFailoverResolution.ignored());
        }

        Player player = nonNullEvent.getPlayer();
        UUID playerId = player.getUniqueId();

        if (!sessionRegistry.isAuthenticated(playerId)) {
            return completed(BackendKickFailoverResolution.ignored());
        }

        String failedServerName = nonNullEvent
                .getServer()
                .getServerInfo()
                .getName();

        Optional<BackendIdentity> identityOptional =
                identityRegistry.find(failedServerName);

        if (identityOptional.isEmpty()) {
            return completed(disconnect(nonNullEvent));
        }

        BackendIdentity identity = identityOptional.orElseThrow();
        if (!identity.serverName().equals(failedServerName)) {
            return completed(disconnect(nonNullEvent));
        }

        BackendType sourceType = identity.backendType();
        if (sourceType == BackendType.AUTH) {
            return completed(disconnect(nonNullEvent));
        }

        Set<String> exclusions = new HashSet<>();
        exclusions.add(failedServerName);
        currentServerName(player).ifPresent(exclusions::add);

        Component disconnectReason = disconnectReason(nonNullEvent);
        final CompletionStage<BackendKickFailoverResolution> stage;
        try {
            stage = coordinator.resolve(
                    player,
                    sourceType,
                    Set.copyOf(exclusions),
                    disconnectReason
            );
        } catch (RuntimeException exception) {
            return completed(
                    BackendKickFailoverResolution.disconnect(disconnectReason)
            );
        }

        if (stage == null) {
            return completed(
                    BackendKickFailoverResolution.disconnect(disconnectReason)
            );
        }

        return stage.handle((resolution, failure) -> {
            if (failure != null || resolution == null) {
                return BackendKickFailoverResolution.disconnect(
                        disconnectReason
                );
            }
            return resolution;
        });
    }

    public void completeSuccessfulConnection(
            UUID playerId,
            String connectedBackendName
    ) {
        coordinator.completeSuccessfulConnection(
                Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                ),
                Objects.requireNonNull(
                        connectedBackendName,
                        "connectedBackendName cannot be null"
                )
        );
    }

    public void cancelPendingFailover(UUID playerId) {
        coordinator.cancelPendingFailover(
                Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                )
        );
    }

    private Optional<String> currentServerName(Player player) {
        return player
                .getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(serverInfo -> serverInfo.getName());
    }

    private BackendKickFailoverResolution disconnect(
            KickedFromServerEvent event
    ) {
        return BackendKickFailoverResolution.disconnect(
                disconnectReason(event)
        );
    }

    private Component disconnectReason(KickedFromServerEvent event) {
        return event.getServerKickReason().orElse(NO_SAFE_TARGET_REASON);
    }

    private static CompletionStage<BackendKickFailoverResolution> completed(
            BackendKickFailoverResolution resolution
    ) {
        return CompletableFuture.completedFuture(resolution);
    }
}

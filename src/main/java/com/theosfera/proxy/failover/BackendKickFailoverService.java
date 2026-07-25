package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.BackendCapacityReservation;
import com.theosfera.proxy.transfer.BackendCapacityReservationResult;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolutionStatus;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class BackendKickFailoverService {

    private static final Component NO_SAFE_TARGET_REASON =
            Component.text(
                    "No hay servidores seguros disponibles en este momento. "
                            + "Inténtalo nuevamente más tarde."
            );

    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final BackendIdentityRegistry identityRegistry;
    private final TransferTargetResolver targetResolver;
    private final BackendBootstrapRegistry bootstrapRegistry;
    private final PendingPlayerFailoverRegistry failoverRegistry;
    private final Clock clock;
    private final Supplier<UUID> requestIdGenerator;

    public BackendKickFailoverService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            BackendIdentityRegistry identityRegistry,
            TransferTargetResolver targetResolver,
            BackendBootstrapRegistry bootstrapRegistry,
            PendingPlayerFailoverRegistry failoverRegistry
    ) {
        this(
                sessionRegistry,
                identityRegistry,
                targetResolver,
                bootstrapRegistry,
                failoverRegistry,
                Clock.systemUTC(),
                UUID::randomUUID
        );
    }

    BackendKickFailoverService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            BackendIdentityRegistry identityRegistry,
            TransferTargetResolver targetResolver,
            BackendBootstrapRegistry bootstrapRegistry,
            PendingPlayerFailoverRegistry failoverRegistry,
            Clock clock,
            Supplier<UUID> requestIdGenerator
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

        this.bootstrapRegistry = Objects.requireNonNull(
                bootstrapRegistry,
                "bootstrapRegistry cannot be null"
        );

        this.failoverRegistry = Objects.requireNonNull(
                failoverRegistry,
                "failoverRegistry cannot be null"
        );

        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );

        this.requestIdGenerator = Objects.requireNonNull(
                requestIdGenerator,
                "requestIdGenerator cannot be null"
        );
    }

    public BackendKickFailoverResolution resolveFailoverTarget(
            KickedFromServerEvent event
    ) {
        KickedFromServerEvent nonNullEvent =
                Objects.requireNonNull(
                        event,
                        "event cannot be null"
                );

        if (nonNullEvent.kickedDuringServerConnect()) {
            return BackendKickFailoverResolution.ignored();
        }

        Player player =
                nonNullEvent.getPlayer();

        UUID playerId =
                player.getUniqueId();

        if (!sessionRegistry.isAuthenticated(playerId)) {
            return BackendKickFailoverResolution.ignored();
        }

        if (failoverRegistry.isReserved(playerId)) {
            return BackendKickFailoverResolution.ignored();
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
            return disconnect(nonNullEvent);
        }

        BackendIdentity identity =
                identityOptional.get();

        if (!identity.serverName().equals(failedServerName)) {
            return disconnect(nonNullEvent);
        }

        BackendType sourceType =
                identity.backendType();

        if (sourceType == BackendType.AUTH) {
            return disconnect(nonNullEvent);
        }

        Set<String> exclusions =
                Set.of(failedServerName);

        Optional<BackendKickFailoverResolution> sameTypeTarget =
                reserveAvailableTarget(
                        playerId,
                        sourceType,
                        exclusions,
                        failedServerName,
                        currentServerName
                );

        if (sameTypeTarget.isPresent()) {
            return sameTypeTarget.orElseThrow();
        }

        if (sourceType == BackendType.LOBBY) {
            return disconnect(nonNullEvent);
        }

        Optional<BackendKickFailoverResolution> lobbyTarget =
                reserveAvailableTarget(
                        playerId,
                        BackendType.LOBBY,
                        exclusions,
                        failedServerName,
                        currentServerName
                );

        return lobbyTarget.orElseGet(
                () -> disconnect(nonNullEvent)
        );
    }

    public void clearPendingFailover(UUID playerId) {
        failoverRegistry
                .clearCapacityForCompletion(playerId)
                .ifPresent(targetResolver::releaseCapacity);
    }

    public void cancelPendingFailover(UUID playerId) {
        failoverRegistry
                .clearForDisconnect(playerId)
                .ifPresent(bootstrapRegistry::removeIfMatches);

        failoverRegistry
                .removeCapacityReservation(playerId)
                .ifPresent(targetResolver::releaseCapacity);
    }

    private Optional<BackendKickFailoverResolution>
    reserveAvailableTarget(
            UUID playerId,
            BackendType targetType,
            Set<String> initialExclusions,
            String failedServerName,
            Optional<String> currentServerName
    ) {
        Set<String> exclusions =
                new HashSet<>(initialExclusions);

        while (true) {
            Optional<TransferTargetResolution> targetResolution =
                    safeTargetResolution(
                            targetType,
                            exclusions,
                            failedServerName,
                            currentServerName
                    );

            if (targetResolution.isEmpty()) {
                return Optional.empty();
            }

            RegisteredServer target =
                    targetResolution
                            .orElseThrow()
                            .resolvedTarget()
                            .orElseThrow();

            String targetName =
                    target.getServerInfo().getName();

            BackendCapacityReservation capacityReservation =
                    new BackendCapacityReservation(
                            requestIdGenerator.get(),
                            playerId,
                            targetName
                    );

            BackendCapacityReservationResult reservationResult =
                    targetResolver.reserveCapacity(
                            capacityReservation,
                            target
                    );

            switch (reservationResult) {
                case RESERVED -> {
                    if (!failoverRegistry.reserve(
                            playerId,
                            capacityReservation
                    )) {
                        targetResolver.releaseCapacity(
                                capacityReservation
                        );

                        return Optional.of(
                                BackendKickFailoverResolution.ignored()
                        );
                    }

                    return Optional.of(
                            BackendKickFailoverResolution.redirect(
                                    target
                            )
                    );
                }

                case NO_CAPACITY -> {
                    if (!exclusions.add(targetName)) {
                        return Optional.empty();
                    }
                }

                case ALREADY_RESERVED, REQUEST_ID_CONFLICT -> {
                    return Optional.of(
                            BackendKickFailoverResolution.disconnect(
                                    NO_SAFE_TARGET_REASON
                            )
                    );
                }
            }
        }
    }

    private Optional<TransferTargetResolution>
    safeTargetResolution(
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
                        Set.copyOf(exclusions)
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
                )
                .map(ignored -> resolution);
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

    private BackendKickFailoverResolution disconnect(
            KickedFromServerEvent event
    ) {
        Component reason =
                event.getServerKickReason()
                        .orElse(NO_SAFE_TARGET_REASON);

        return BackendKickFailoverResolution.disconnect(reason);
    }
}

package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.BackendCapacityHandoffLifecycle;
import com.theosfera.proxy.coordination.PlayerPresenceCoordinator;
import com.theosfera.proxy.coordination.PlayerPresencePublishRequest;
import com.theosfera.proxy.coordination.PlayerPresencePublishResult;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveRequest;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class PlayerPresenceRuntimeService {

    private final ProxyServer proxyServer;
    private final PlayerPresenceCoordinator coordinator;
    private final PlayerSessionLeaseBindingRegistry bindingRegistry;
    private final PlayerServerPresenceRegistry localRegistry;
    private final PlayerPresenceRenewalScheduler scheduler;
    private final Duration renewInterval;
    private final Logger logger;

    private volatile BackendCapacityHandoffLifecycle capacityHandoffLifecycle;
    private PlayerPresenceRenewalScheduler.Handle renewalTask;

    public PlayerPresenceRuntimeService(
            ProxyServer proxyServer,
            PlayerPresenceCoordinator coordinator,
            PlayerSessionLeaseBindingRegistry bindingRegistry,
            PlayerServerPresenceRegistry localRegistry,
            PlayerPresenceRenewalScheduler scheduler,
            Duration renewInterval,
            Logger logger
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator cannot be null"
        );
        this.bindingRegistry = Objects.requireNonNull(
                bindingRegistry,
                "bindingRegistry cannot be null"
        );
        this.localRegistry = Objects.requireNonNull(
                localRegistry,
                "localRegistry cannot be null"
        );
        this.scheduler = Objects.requireNonNull(
                scheduler,
                "scheduler cannot be null"
        );
        this.renewInterval = requirePositive(renewInterval);
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public synchronized void start() {
        if (renewalTask != null) {
            throw new IllegalStateException(
                    "Player presence runtime is already started"
            );
        }
        renewalTask = scheduler.schedule(
                this::renewPublishedPresences,
                renewInterval
        );
    }

    public synchronized void stop() {
        PlayerPresenceRenewalScheduler.Handle current = renewalTask;
        renewalTask = null;
        if (current != null) {
            current.cancel();
        }
    }

    public synchronized void configureCapacityHandoffLifecycle(
            BackendCapacityHandoffLifecycle lifecycle
    ) {
        if (renewalTask != null) {
            throw new IllegalStateException(
                    "capacity handoff lifecycle must be configured before presence runtime starts"
            );
        }
        if (capacityHandoffLifecycle != null) {
            throw new IllegalStateException(
                    "capacity handoff lifecycle is already configured"
            );
        }
        capacityHandoffLifecycle = Objects.requireNonNull(
                lifecycle,
                "lifecycle cannot be null"
        );
    }

    public PlayerPresenceUpdateResult publishReady(
            Player player,
            PlayerServerPresence presence
    ) {
        Player nonNullPlayer = Objects.requireNonNull(
                player,
                "player cannot be null"
        );
        PlayerServerPresence nonNullPresence = Objects.requireNonNull(
                presence,
                "presence cannot be null"
        );

        if (!nonNullPlayer.getUniqueId().equals(nonNullPresence.playerId())) {
            throw new IllegalArgumentException(
                    "player identity must match presence"
            );
        }

        PlayerPresenceUpdateResult localResult =
                localRegistry.update(nonNullPresence);

        if (localResult == PlayerPresenceUpdateResult.RECORDED
                || localResult == PlayerPresenceUpdateResult.UPDATED
                || localResult == PlayerPresenceUpdateResult.ALREADY_RECORDED) {
            bindingRegistry.find(nonNullPlayer).ifPresentOrElse(
                    lease -> publishDistributed(nonNullPresence, lease),
                    () -> logger.warn(
                            "Presencia local para {} no se publico en Redis porque no existe un lease de sesion vinculado.",
                            nonNullPresence.playerId()
                    )
            );
        }

        return localResult;
    }

    public CompletionStage<PlayerPresenceRemoveResult> removeIfOwned(
            PlayerSessionLease lease,
            PlayerServerPresence presence
    ) {
        PlayerSessionLease nonNullLease = Objects.requireNonNull(
                lease,
                "lease cannot be null"
        );
        PlayerServerPresence nonNullPresence = Objects.requireNonNull(
                presence,
                "presence cannot be null"
        );

        if (!nonNullLease.session().playerId().equals(nonNullPresence.playerId())) {
            throw new IllegalArgumentException(
                    "lease identity must match presence"
            );
        }

        return coordinator.removeIfOwned(
                new PlayerPresenceRemoveRequest(
                        nonNullLease,
                        nonNullPresence.backendName(),
                        nonNullPresence.readyAt()
                )
        );
    }

    private void renewPublishedPresences() {
        Map<UUID, PlayerServerPresence> snapshot = localRegistry.snapshot();

        for (PlayerServerPresence presence : snapshot.values()) {
            Optional<Player> player = proxyServer.getPlayer(presence.playerId());
            if (player.isEmpty()) {
                continue;
            }

            Optional<PlayerSessionLease> lease =
                    bindingRegistry.find(player.orElseThrow());
            if (lease.isEmpty()) {
                continue;
            }

            publishDistributed(presence, lease.orElseThrow());
        }
    }

    private void publishDistributed(
            PlayerServerPresence presence,
            PlayerSessionLease lease
    ) {
        final CompletionStage<PlayerPresencePublishResult> stage;
        try {
            stage = coordinator.publish(
                    new PlayerPresencePublishRequest(
                            lease,
                            presence.backendName(),
                            presence.readyAt(),
                            presence.readyAt()
                    )
            );
        } catch (RuntimeException exception) {
            logger.error(
                    "No se pudo iniciar la publicacion Redis de presencia para {}.",
                    presence.playerId(),
                    exception
            );
            return;
        }

        Objects.requireNonNull(
                stage,
                "coordinator.publish returned null"
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                logger.error(
                        "Fallo al publicar presencia Redis para {}.",
                        presence.playerId(),
                        failure
                );
                return;
            }

            switch (result.status()) {
                case RECORDED, UPDATED -> {
                    logger.debug(
                            "Presencia Redis publicada para {} en {}.",
                            presence.playerId(),
                            presence.backendName()
                    );
                    confirmCapacityHandoff(presence, lease);
                }
                case ALREADY_RECORDED -> {
                    logger.trace(
                            "Presencia Redis renovada para {} en {}.",
                            presence.playerId(),
                            presence.backendName()
                    );
                    confirmCapacityHandoff(presence, lease);
                }
                case COORDINATION_UNAVAILABLE -> logger.warn(
                        "No se pudo renovar/publicar presencia Redis para {} porque la coordinacion no esta disponible.",
                        presence.playerId()
                );
                case SESSION_NOT_FOUND, NOT_SESSION_OWNER -> logger.warn(
                        "Presencia Redis rechazada para {} porque el lease de sesion ya no es autoritativo ({}).",
                        presence.playerId(),
                        result.status()
                );
                case STALE, CONFLICT -> logger.warn(
                        "Presencia Redis rechazada para {} por {}.",
                        presence.playerId(),
                        result.status()
                );
            }
        });
    }

    private void confirmCapacityHandoff(
            PlayerServerPresence presence,
            PlayerSessionLease lease
    ) {
        BackendCapacityHandoffLifecycle lifecycle = capacityHandoffLifecycle;
        if (lifecycle == null) {
            return;
        }

        try {
            lifecycle.onPresenceConfirmed(
                    lease,
                    presence.backendName()
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "Presencia Redis confirmada para {} en {}, pero fallo el callback de handoff de capacidad.",
                    presence.playerId(),
                    presence.backendName(),
                    exception
            );
        }
    }

    private static Duration requirePositive(Duration interval) {
        Duration nonNull = Objects.requireNonNull(
                interval,
                "renewInterval cannot be null"
        );
        if (nonNull.isZero() || nonNull.isNegative()) {
            throw new IllegalArgumentException(
                    "renewInterval must be positive"
            );
        }
        return nonNull;
    }
}

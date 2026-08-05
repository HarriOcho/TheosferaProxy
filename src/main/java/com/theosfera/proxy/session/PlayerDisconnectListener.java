package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.BackendCapacityHandoffLifecycle;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.transfer.BackendCapacityReservationRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class PlayerDisconnectListener {

    private final PlayerSessionLeaseBindingRegistry leaseBindingRegistry;
    private final PlayerSessionReleaseService releaseService;
    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final PlayerServerPresenceRegistry presenceRegistry;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final PlayerPresenceRuntimeService presenceRuntimeService;
    private final Logger logger;

    private volatile BackendCapacityHandoffLifecycle capacityHandoffLifecycle;

    public PlayerDisconnectListener(
            PlayerSessionLeaseBindingRegistry leaseBindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerSessionReleaseService releaseService,
            Logger logger
    ) {
        this(
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                sessionRegistry,
                releaseService,
                null,
                logger
        );
    }

    public PlayerDisconnectListener(
            PlayerSessionLeaseBindingRegistry leaseBindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerSessionReleaseService releaseService,
            PlayerPresenceRuntimeService presenceRuntimeService,
            Logger logger
    ) {
        this.leaseBindingRegistry = Objects.requireNonNull(
                leaseBindingRegistry,
                "leaseBindingRegistry cannot be null"
        );
        this.presenceRegistry = Objects.requireNonNull(
                presenceRegistry,
                "presenceRegistry cannot be null"
        );
        this.transferRegistry = Objects.requireNonNull(
                transferRegistry,
                "transferRegistry cannot be null"
        );
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
        this.releaseService = Objects.requireNonNull(
                releaseService,
                "releaseService cannot be null"
        );
        this.presenceRuntimeService = presenceRuntimeService;
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    /**
     * Compatibility constructor for tests and callers compiled against the
     * pre-Redis-capacity cleanup API. The local capacity registry is no longer
     * an authority and is deliberately ignored.
     */
    @Deprecated(forRemoval = true)
    public PlayerDisconnectListener(
            PlayerSessionLeaseBindingRegistry leaseBindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendCapacityReservationRegistry ignoredCapacityRegistry,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerSessionReleaseService releaseService,
            Logger logger
    ) {
        this(
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                requireLegacyRegistry(ignoredCapacityRegistry),
                sessionRegistry,
                releaseService,
                null,
                logger
        );
    }

    /**
     * Compatibility constructor for tests and callers compiled against the
     * pre-Redis-capacity cleanup API. The local capacity registry is no longer
     * an authority and is deliberately ignored.
     */
    @Deprecated(forRemoval = true)
    public PlayerDisconnectListener(
            PlayerSessionLeaseBindingRegistry leaseBindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendCapacityReservationRegistry ignoredCapacityRegistry,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerSessionReleaseService releaseService,
            PlayerPresenceRuntimeService presenceRuntimeService,
            Logger logger
    ) {
        this(
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                sessionRegistry,
                releaseService,
                presenceRuntimeService,
                logger
        );
        requireLegacyRegistry(ignoredCapacityRegistry);
    }

    public synchronized void configureCapacityHandoffLifecycle(
            BackendCapacityHandoffLifecycle lifecycle
    ) {
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

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        Optional<PendingPlayerTransfer> removedTransfer =
                transferRegistry.removeByPlayer(playerId);

        Optional<PlayerServerPresence> removedPresence =
                presenceRegistry.remove(playerId);

        Optional<PlayerSessionLease> lease;
        boolean authenticationRemoved;

        synchronized (leaseBindingRegistry) {
            Optional<PlayerSessionLease> authenticatedLease =
                    leaseBindingRegistry.find(player);
            lease = leaseBindingRegistry.removeForDisconnect(player);
            authenticationRemoved = authenticatedLease
                    .flatMap(ownedLease -> sessionRegistry.removeIfMatches(
                            ownedLease.session()
                    ))
                    .isPresent();
        }

        boolean localStateRemoved = removedTransfer.isPresent()
                || removedPresence.isPresent()
                || authenticationRemoved;

        if (lease.isEmpty()) {
            if (localStateRemoved) {
                logStateRemoval(playerId);
            }
            return;
        }

        PlayerSessionLease ownedLease = lease.orElseThrow();
        if (presenceRuntimeService == null || removedPresence.isEmpty()) {
            releaseCapacityHandoffThenLease(
                    ownedLease,
                    playerId,
                    localStateRemoved
            );
            return;
        }

        try {
            presenceRuntimeService.removeIfOwned(
                    ownedLease,
                    removedPresence.orElseThrow()
            ).whenComplete((result, failure) -> {
                if (failure != null) {
                    logger.warn(
                            "No se pudo retirar la presencia Redis de {} antes de liberar su sesión.",
                            playerId,
                            failure
                    );
                } else if (result.status()
                        == com.theosfera.proxy.coordination.PlayerPresenceRemoveResult.Status
                        .COORDINATION_UNAVAILABLE) {
                    logger.warn(
                            "La presencia Redis de {} no pudo retirarse durante disconnect; TTL actuará como fallback.",
                            playerId
                    );
                }
                releaseCapacityHandoffThenLease(
                        ownedLease,
                        playerId,
                        localStateRemoved
                );
            });
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo iniciar la retirada de presencia Redis para {}.",
                    playerId,
                    exception
            );
            releaseCapacityHandoffThenLease(
                    ownedLease,
                    playerId,
                    localStateRemoved
            );
        }
    }

    private void releaseCapacityHandoffThenLease(
            PlayerSessionLease lease,
            UUID playerId,
            boolean localStateRemoved
    ) {
        BackendCapacityHandoffLifecycle lifecycle = capacityHandoffLifecycle;
        if (lifecycle == null) {
            releaseLease(lease, playerId, localStateRemoved);
            return;
        }

        final CompletionStage<Boolean> stage;
        try {
            stage = lifecycle.releaseForDisconnect(lease);
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo iniciar la liberacion del handoff de capacidad para {} durante disconnect; TTL actuara como fallback.",
                    playerId,
                    exception
            );
            releaseLease(lease, playerId, localStateRemoved);
            return;
        }

        if (stage == null) {
            logger.warn(
                    "La liberacion del handoff de capacidad para {} devolvio un stage nulo durante disconnect; TTL actuara como fallback.",
                    playerId
            );
            releaseLease(lease, playerId, localStateRemoved);
            return;
        }

        stage.whenComplete((released, failure) -> {
            if (failure != null) {
                logger.warn(
                        "Fallo la liberacion del handoff de capacidad para {} durante disconnect; TTL actuara como fallback.",
                        playerId,
                        failure
                );
            } else if (!Boolean.TRUE.equals(released)) {
                logger.debug(
                        "No se confirmo la liberacion exacta del handoff de capacidad para {} durante disconnect.",
                        playerId
                );
            }

            releaseLease(lease, playerId, localStateRemoved);
        });
    }

    private void releaseLease(
            PlayerSessionLease lease,
            UUID playerId,
            boolean localStateRemoved
    ) {
        releaseService.releaseIfUnbound(
                lease,
                new PlayerSessionReleaseService.ReleaseCallbacks() {
                    @Override
                    public void onNotReserved(PlayerSessionLease ignored) {
                        if (localStateRemoved) {
                            logStateRemoval(playerId);
                        }
                    }

                    @Override
                    public void onStartFailure(
                            PlayerSessionLease ignored,
                            RuntimeException failure
                    ) {
                        logger.error(
                                "No se pudo iniciar la liberación del lease de sesión para {}.",
                                playerId,
                                failure
                        );
                        if (localStateRemoved) {
                            logStateRemoval(playerId);
                        }
                    }

                    @Override
                    public void onFailure(
                            PlayerSessionLease ignored,
                            Throwable failure
                    ) {
                        logger.error(
                                "No se pudo liberar el lease de sesión para {}.",
                                playerId,
                                failure
                        );
                        if (localStateRemoved) {
                            logStateRemoval(playerId);
                        }
                    }

                    @Override
                    public void onComplete(
                            PlayerSessionLease ignored,
                            boolean released
                    ) {
                        if (!released) {
                            logger.debug(
                                    "El lease de sesión para {} ya no coincidía con la propiedad vigente.",
                                    playerId
                            );
                        }
                        if (localStateRemoved || released) {
                            logStateRemoval(playerId);
                        }
                    }
                }
        );
    }

    private void logStateRemoval(UUID playerId) {
        logger.debug(
                "Estado de sesión eliminado para {} al desconectarse del proxy.",
                playerId
        );
    }

    private static BackendCapacityReservationRegistry requireLegacyRegistry(
            BackendCapacityReservationRegistry registry
    ) {
        return Objects.requireNonNull(
                registry,
                "capacityRegistry cannot be null"
        );
    }
}

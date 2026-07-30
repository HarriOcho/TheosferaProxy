package com.theosfera.proxy.session;

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

public final class PlayerDisconnectListener {

    private final PlayerSessionLeaseBindingRegistry
            leaseBindingRegistry;
    private final PlayerSessionReleaseService releaseService;
    private final PlayerServerPresenceRegistry
            presenceRegistry;
    private final PendingPlayerTransferRegistry
            transferRegistry;
    private final BackendCapacityReservationRegistry
            capacityRegistry;
    private final Logger logger;

    public PlayerDisconnectListener(
            PlayerSessionLeaseBindingRegistry
                    leaseBindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            PlayerSessionReleaseService releaseService,
            Logger logger
    ) {
        this(
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                new BackendCapacityReservationRegistry(),
                releaseService,
                logger
        );
    }

    public PlayerDisconnectListener(
            PlayerSessionLeaseBindingRegistry
                    leaseBindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendCapacityReservationRegistry capacityRegistry,
            PlayerSessionReleaseService releaseService,
            Logger logger
    ) {
        this.leaseBindingRegistry =
                Objects.requireNonNull(
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

        this.capacityRegistry = Objects.requireNonNull(
                capacityRegistry,
                "capacityRegistry cannot be null"
        );

        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );

        this.releaseService = Objects.requireNonNull(
                releaseService,
                "releaseService cannot be null"
        );
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Objects.requireNonNull(
                event,
                "event cannot be null"
        );

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        Optional<PendingPlayerTransfer> removedTransfer =
                transferRegistry
                        .removeByPlayer(playerId);

        removedTransfer.ifPresent(transfer ->
                capacityRegistry.removeByRequest(
                        transfer.requestId()
                )
        );

        boolean transferRemoved =
                removedTransfer.isPresent();

        boolean presenceRemoved =
                presenceRegistry
                        .remove(playerId)
                        .isPresent();

        Optional<PlayerSessionLease> lease =
                leaseBindingRegistry
                        .removeForDisconnect(player);

        boolean localStateRemoved =
                transferRemoved || presenceRemoved;

        if (lease.isEmpty()) {
            if (localStateRemoved) {
                logStateRemoval(playerId);
            }

            return;
        }

        releaseLease(
                lease.orElseThrow(),
                playerId,
                localStateRemoved
        );
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
                    public void onNotReserved(
                            PlayerSessionLease ignored
                    ) {
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
                                "No se pudo iniciar la liberación "
                                        + "del lease de sesión para {}.",
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
                                "No se pudo liberar el lease "
                                        + "de sesión para {}.",
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
                                    "El lease de sesión para {} "
                                            + "ya no coincidía con la "
                                            + "propiedad vigente.",
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
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                playerId
        );
    }

}

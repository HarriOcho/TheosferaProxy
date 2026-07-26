package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
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

    private final PlayerSessionCoordinator sessionCoordinator;
    private final PlayerSessionLeaseBindingRegistry
            leaseBindingRegistry;
    private final PlayerServerPresenceRegistry
            presenceRegistry;
    private final PendingPlayerTransferRegistry
            transferRegistry;
    private final BackendCapacityReservationRegistry
            capacityRegistry;
    private final Logger logger;

    public PlayerDisconnectListener(
            PlayerSessionCoordinator sessionCoordinator,
            PlayerSessionLeaseBindingRegistry
                    leaseBindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            Logger logger
    ) {
        this(
                sessionCoordinator,
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                new BackendCapacityReservationRegistry(),
                logger
        );
    }

    public PlayerDisconnectListener(
            PlayerSessionCoordinator sessionCoordinator,
            PlayerSessionLeaseBindingRegistry
                    leaseBindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendCapacityReservationRegistry capacityRegistry,
            Logger logger
    ) {
        this.sessionCoordinator = Objects.requireNonNull(
                sessionCoordinator,
                "sessionCoordinator cannot be null"
        );

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
        boolean releaseReserved =
                leaseBindingRegistry
                        .reserveReleaseIfUnbound(lease);

        if (!releaseReserved) {
            if (localStateRemoved) {
                logStateRemoval(playerId);
            }

            return;
        }

        CompletionStage<Boolean> releaseStage;

        try {
            releaseStage = Objects.requireNonNull(
                    sessionCoordinator.releaseIfOwned(lease),
                    "sessionCoordinator.releaseIfOwned "
                            + "returned null"
            );
        } catch (RuntimeException exception) {
            leaseBindingRegistry.failRelease(
                    lease,
                    exception
            );

            logger.error(
                    "No se pudo iniciar la liberación del lease "
                            + "de sesión para {}.",
                    playerId,
                    exception
            );

            if (localStateRemoved) {
                logStateRemoval(playerId);
            }

            return;
        }

        releaseStage.whenComplete(
                (released, failure) -> {
                    if (failure != null) {
                        leaseBindingRegistry.failRelease(
                                lease,
                                failure
                        );

                        logger.error(
                                "No se pudo liberar el lease "
                                        + "de sesión para {}.",
                                playerId,
                                failure
                        );

                        if (localStateRemoved) {
                            logStateRemoval(playerId);
                        }

                        return;
                    }

                    boolean releaseSucceeded =
                            Boolean.TRUE.equals(released);

                    leaseBindingRegistry.completeRelease(
                            lease,
                            releaseSucceeded
                    );

                    if (!releaseSucceeded) {
                        logger.debug(
                                "El lease de sesión para {} "
                                        + "ya no coincidía con la "
                                        + "propiedad vigente.",
                                playerId
                        );
                    }

                    if (localStateRemoved
                            || Boolean.TRUE.equals(released)) {
                        logStateRemoval(playerId);
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

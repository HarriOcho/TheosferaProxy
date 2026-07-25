package com.theosfera.proxy.session;

import com.theosfera.proxy.transfer.BackendCapacityReservationRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerDisconnectListener {

    private final AuthenticatedPlayerSessionRegistry
            sessionRegistry;
    private final PlayerServerPresenceRegistry
            presenceRegistry;
    private final PendingPlayerTransferRegistry
            transferRegistry;
    private final BackendCapacityReservationRegistry
            capacityRegistry;
    private final Logger logger;

    public PlayerDisconnectListener(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            Logger logger
    ) {
        this(
                sessionRegistry,
                presenceRegistry,
                transferRegistry,
                new BackendCapacityReservationRegistry(),
                logger
        );
    }

    public PlayerDisconnectListener(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendCapacityReservationRegistry capacityRegistry,
            Logger logger
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
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

        UUID playerId =
                event.getPlayer().getUniqueId();

        Optional<PendingPlayerTransfer> removedTransfer =
                transferRegistry
                        .removeByPlayer(playerId);

        removedTransfer.ifPresent(transfer ->
                capacityRegistry.removeByRequest(
                        transfer.requestId()
                )
        );

        boolean transferRemoved = removedTransfer.isPresent();

        boolean presenceRemoved =
                presenceRegistry
                        .remove(playerId)
                        .isPresent();

        boolean sessionRemoved =
                sessionRegistry
                        .remove(playerId)
                        .isPresent();

        if (transferRemoved
                || presenceRemoved
                || sessionRemoved) {
            logger.debug(
                    "Estado de sesión eliminado para {} "
                            + "al desconectarse del proxy.",
                    playerId
            );
        }
    }
}

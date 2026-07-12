package com.theosfera.proxy.session;

import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;

public final class PlayerDisconnectListener {

    private final AuthenticatedPlayerSessionRegistry
            sessionRegistry;
    private final PlayerServerPresenceRegistry
            presenceRegistry;
    private final PendingPlayerTransferRegistry
            transferRegistry;
    private final Logger logger;

    public PlayerDisconnectListener(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
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

        boolean transferRemoved =
                transferRegistry
                        .removeByPlayer(playerId)
                        .isPresent();

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
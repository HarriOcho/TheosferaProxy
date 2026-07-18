package com.theosfera.proxy.failover;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;

import java.util.Objects;

public final class BackendKickFailoverListener {

    private final BackendKickFailoverService failoverService;

    public BackendKickFailoverListener(
            BackendKickFailoverService failoverService
    ) {
        this.failoverService = Objects.requireNonNull(
                failoverService,
                "failoverService cannot be null"
        );
    }

    @Subscribe
    public void onKickedFromServer(
            KickedFromServerEvent event
    ) {
        KickedFromServerEvent nonNullEvent =
                Objects.requireNonNull(
                        event,
                        "event cannot be null"
                );

        failoverService
                .resolveFailoverTarget(nonNullEvent)
                .map(KickedFromServerEvent.RedirectPlayer::create)
                .ifPresent(nonNullEvent::setResult);
    }

    @Subscribe
    public void onServerConnected(
            ServerConnectedEvent event
    ) {
        ServerConnectedEvent nonNullEvent =
                Objects.requireNonNull(
                        event,
                        "event cannot be null"
                );

        failoverService.clearPendingFailover(
                nonNullEvent.getPlayer().getUniqueId()
        );
    }

    @Subscribe
    public void onDisconnect(
            DisconnectEvent event
    ) {
        DisconnectEvent nonNullEvent =
                Objects.requireNonNull(
                        event,
                        "event cannot be null"
                );

        failoverService.clearPendingFailover(
                nonNullEvent.getPlayer().getUniqueId()
        );
    }
}

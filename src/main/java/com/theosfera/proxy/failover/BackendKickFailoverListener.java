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

        BackendKickFailoverResolution resolution =
                failoverService.resolveFailoverTarget(
                        nonNullEvent
                );

        switch (resolution.status()) {
            case IGNORED -> {
                // Preserve Velocity's existing result.
            }

            case REDIRECT -> nonNullEvent.setResult(
                    KickedFromServerEvent.RedirectPlayer.create(
                            resolution.redirectTarget()
                                    .orElseThrow(() ->
                                            new IllegalStateException(
                                                    "redirect resolution has no target"
                                            )
                                    )
                    )
            );

            case DISCONNECT -> nonNullEvent.setResult(
                    KickedFromServerEvent.DisconnectPlayer.create(
                            resolution.reason()
                                    .orElseThrow(() ->
                                            new IllegalStateException(
                                                    "disconnect resolution has no reason"
                                            )
                                    )
                    )
            );
        }
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

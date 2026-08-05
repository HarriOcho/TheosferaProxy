package com.theosfera.proxy.failover;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.theosfera.proxy.ui.TheosferaPalette.GOLD;
import static com.theosfera.proxy.ui.TheosferaPalette.LIGHT_GOLD;
import static com.theosfera.proxy.ui.TheosferaPalette.SECONDARY_TEXT;
import static com.theosfera.proxy.ui.TheosferaPalette.WARM_IVORY;

public final class BackendKickFailoverListener {

    private static final Component NO_SAFE_TARGET_REASON =
            Component.text(
                    "No hay servidores seguros disponibles en este momento. "
                            + "Inténtalo nuevamente más tarde.",
                    WARM_IVORY
            );

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
    public EventTask onKickedFromServer(
            KickedFromServerEvent event
    ) {
        KickedFromServerEvent nonNullEvent = Objects.requireNonNull(
                event,
                "event cannot be null"
        );

        final CompletionStage<BackendKickFailoverResolution> stage;
        try {
            stage = failoverService.resolveFailoverTarget(nonNullEvent);
        } catch (RuntimeException exception) {
            applyFailClosed(nonNullEvent);
            return EventTask.resumeWhenComplete(
                    CompletableFuture.completedFuture(null)
            );
        }

        if (stage == null) {
            applyFailClosed(nonNullEvent);
            return EventTask.resumeWhenComplete(
                    CompletableFuture.completedFuture(null)
            );
        }

        CompletableFuture<Void> completion = stage
                .handle((resolution, failure) -> {
                    if (failure != null || resolution == null) {
                        applyFailClosed(nonNullEvent);
                        return (Void) null;
                    }

                    applyResolution(nonNullEvent, resolution);
                    return (Void) null;
                })
                .toCompletableFuture();

        return EventTask.resumeWhenComplete(completion);
    }

    @Subscribe
    public void onServerConnected(
            ServerConnectedEvent event
    ) {
        ServerConnectedEvent nonNullEvent = Objects.requireNonNull(
                event,
                "event cannot be null"
        );

        failoverService.completeSuccessfulConnection(
                nonNullEvent.getPlayer().getUniqueId(),
                nonNullEvent.getServer().getServerInfo().getName()
        );
    }

    @Subscribe
    public void onDisconnect(
            DisconnectEvent event
    ) {
        DisconnectEvent nonNullEvent = Objects.requireNonNull(
                event,
                "event cannot be null"
        );

        failoverService.cancelPendingFailover(
                nonNullEvent.getPlayer().getUniqueId()
        );
    }

    private void applyResolution(
            KickedFromServerEvent event,
            BackendKickFailoverResolution resolution
    ) {
        switch (resolution.status()) {
            case IGNORED -> {
                // Preserve Velocity's existing result.
            }

            case REDIRECT -> {
                RegisteredServer target = resolution.redirectTarget()
                        .orElseThrow(() -> new IllegalStateException(
                                "redirect resolution has no target"
                        ));
                event.setResult(
                        KickedFromServerEvent.RedirectPlayer.create(
                                target,
                                redirectMessage(target)
                        )
                );
            }

            case DISCONNECT -> event.setResult(
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

    private Component redirectMessage(RegisteredServer target) {
        String serverName = target.getServerInfo().getName();
        String displayName = Character.toUpperCase(serverName.charAt(0))
                + serverName.substring(1);

        return Component.text("Redireccionando a ", GOLD)
                .append(Component.text(displayName, LIGHT_GOLD))
                .append(Component.text("...", SECONDARY_TEXT));
    }

    private void applyFailClosed(KickedFromServerEvent event) {
        event.setResult(
                KickedFromServerEvent.DisconnectPlayer.create(
                        event.getServerKickReason()
                                .orElse(NO_SAFE_TARGET_REASON)
                )
        );
    }
}

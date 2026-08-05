package com.theosfera.proxy.failover;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.theosfera.proxy.ui.TheosferaPalette.GOLD;
import static com.theosfera.proxy.ui.TheosferaPalette.LIGHT_GOLD;
import static com.theosfera.proxy.ui.TheosferaPalette.SECONDARY_TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendKickFailoverListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString(
            "5b8578de-76a6-467f-9211-399b3dfe723a"
    );
    private static final Component REASON =
            Component.text("Sin conexion con el backend.");

    @Test
    void awaitedResolutionRedirectsWhenDistributedStageCompletes() {
        BackendKickFailoverService service = mock(BackendKickFailoverService.class);
        BackendKickFailoverListener listener = new BackendKickFailoverListener(service);
        KickedFromServerEvent event = event(server("skyblock-1"));
        RegisteredServer target = server("skyblock-2");
        CompletableFuture<BackendKickFailoverResolution> future =
                new CompletableFuture<>();
        when(service.resolveFailoverTarget(event)).thenReturn(future);

        KickedFromServerEvent.ServerKickResult original = event.getResult();
        EventTask task = listener.onKickedFromServer(event);

        assertNotNull(task);
        assertSame(original, event.getResult());

        future.complete(BackendKickFailoverResolution.redirect(target));

        KickedFromServerEvent.RedirectPlayer result = assertInstanceOf(
                KickedFromServerEvent.RedirectPlayer.class,
                event.getResult()
        );
        assertSame(target, result.getServer());
        assertEquals(
                Component.text("Redireccionando a ", GOLD)
                        .append(Component.text("Skyblock-2", LIGHT_GOLD))
                        .append(Component.text("...", SECONDARY_TEXT)),
                result.getMessageComponent()
        );
    }

    @Test
    void disconnectResolutionPreservesProvidedReason() {
        BackendKickFailoverService service = mock(BackendKickFailoverService.class);
        BackendKickFailoverListener listener = new BackendKickFailoverListener(service);
        KickedFromServerEvent event = event(server("skyblock-1"));
        when(service.resolveFailoverTarget(event)).thenReturn(
                CompletableFuture.completedFuture(
                        BackendKickFailoverResolution.disconnect(REASON)
                )
        );

        EventTask task = listener.onKickedFromServer(event);

        assertNotNull(task);
        KickedFromServerEvent.DisconnectPlayer result = assertInstanceOf(
                KickedFromServerEvent.DisconnectPlayer.class,
                event.getResult()
        );
        assertSame(REASON, result.getReasonComponent());
    }

    @Test
    void ignoredResolutionPreservesVelocityResult() {
        BackendKickFailoverService service = mock(BackendKickFailoverService.class);
        BackendKickFailoverListener listener = new BackendKickFailoverListener(service);
        KickedFromServerEvent event = event(server("skyblock-1"));
        KickedFromServerEvent.ServerKickResult original = event.getResult();
        when(service.resolveFailoverTarget(event)).thenReturn(
                CompletableFuture.completedFuture(
                        BackendKickFailoverResolution.ignored()
                )
        );

        EventTask task = listener.onKickedFromServer(event);

        assertNotNull(task);
        assertSame(original, event.getResult());
    }

    @Test
    void exceptionalResolutionFailsClosedWithOriginalKickReason() {
        BackendKickFailoverService service = mock(BackendKickFailoverService.class);
        BackendKickFailoverListener listener = new BackendKickFailoverListener(service);
        KickedFromServerEvent event = event(server("skyblock-1"));
        when(service.resolveFailoverTarget(event)).thenReturn(
                CompletableFuture.failedFuture(
                        new RuntimeException("redis unavailable")
                )
        );

        EventTask task = listener.onKickedFromServer(event);

        assertNotNull(task);
        KickedFromServerEvent.DisconnectPlayer result = assertInstanceOf(
                KickedFromServerEvent.DisconnectPlayer.class,
                event.getResult()
        );
        assertSame(REASON, result.getReasonComponent());
    }

    @Test
    void nullResolutionStageFailsClosed() {
        BackendKickFailoverService service = mock(BackendKickFailoverService.class);
        BackendKickFailoverListener listener = new BackendKickFailoverListener(service);
        KickedFromServerEvent event = event(server("skyblock-1"));
        when(service.resolveFailoverTarget(event)).thenReturn(null);

        EventTask task = listener.onKickedFromServer(event);

        assertNotNull(task);
        KickedFromServerEvent.DisconnectPlayer result = assertInstanceOf(
                KickedFromServerEvent.DisconnectPlayer.class,
                event.getResult()
        );
        assertSame(REASON, result.getReasonComponent());
    }

    @Test
    void synchronousServiceFailureFailsClosed() {
        BackendKickFailoverService service = mock(BackendKickFailoverService.class);
        BackendKickFailoverListener listener = new BackendKickFailoverListener(service);
        KickedFromServerEvent event = event(server("skyblock-1"));
        when(service.resolveFailoverTarget(event)).thenThrow(
                new RuntimeException("unexpected failure")
        );

        EventTask task = listener.onKickedFromServer(event);

        assertNotNull(task);
        KickedFromServerEvent.DisconnectPlayer result = assertInstanceOf(
                KickedFromServerEvent.DisconnectPlayer.class,
                event.getResult()
        );
        assertSame(REASON, result.getReasonComponent());
    }

    @Test
    void serverConnectedDelegatesExactConnectedBackend() {
        BackendKickFailoverService service = mock(BackendKickFailoverService.class);
        BackendKickFailoverListener listener = new BackendKickFailoverListener(service);
        Player player = player();
        RegisteredServer connected = server("skyblock-2");

        listener.onServerConnected(
                new ServerConnectedEvent(
                        player,
                        connected,
                        server("skyblock-1")
                )
        );

        verify(service).completeSuccessfulConnection(
                PLAYER_ID,
                "skyblock-2"
        );
    }

    @Test
    void disconnectDelegatesPendingCleanup() {
        BackendKickFailoverService service = mock(BackendKickFailoverService.class);
        BackendKickFailoverListener listener = new BackendKickFailoverListener(service);
        Player player = player();

        listener.onDisconnect(
                new DisconnectEvent(
                        player,
                        DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
                )
        );

        verify(service).cancelPendingFailover(PLAYER_ID);
    }

    @Test
    void rejectsNullDependencyAndEvents() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverListener(null)
        );

        BackendKickFailoverListener listener = new BackendKickFailoverListener(
                mock(BackendKickFailoverService.class)
        );

        assertThrows(
                NullPointerException.class,
                () -> listener.onKickedFromServer(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> listener.onServerConnected(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> listener.onDisconnect(null)
        );
    }

    private KickedFromServerEvent event(RegisteredServer failedServer) {
        return new KickedFromServerEvent(
                player(),
                failedServer,
                REASON,
                false,
                KickedFromServerEvent.Notify.create(
                        Component.text("Destino no disponible.")
                )
        );
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        return player;
    }

    private RegisteredServer server(String serverName) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(serverName);
        return server;
    }
}

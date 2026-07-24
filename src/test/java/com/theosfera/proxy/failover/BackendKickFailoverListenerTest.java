package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendKickFailoverListenerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "5b8578de-76a6-467f-9211-399b3dfe723a"
            );

    @Test
    void redirectsUsingVelocityRedirectPlayerResult() {
        TransferTargetResolver targetResolver =
                mock(TransferTargetResolver.class);
        RegisteredServer target =
                server("skyblock-2");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(target)
        );

        BackendKickFailoverListener listener =
                listener(targetResolver);

        KickedFromServerEvent event =
                event(
                        server("skyblock-1"),
                        false
                );

        listener.onKickedFromServer(event);

        KickedFromServerEvent.RedirectPlayer result =
                assertInstanceOf(
                        KickedFromServerEvent.RedirectPlayer.class,
                        event.getResult()
                );

        assertSame(
                target,
                result.getServer()
        );
    }

    @Test
    void disconnectsAuthenticatedPlayerWithoutSafeTarget() {
        TransferTargetResolver targetResolver =
                mock(TransferTargetResolver.class);

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.notConfigured()
        );

        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.notConfigured()
        );

        BackendKickFailoverListener listener =
                listener(targetResolver);

        KickedFromServerEvent event =
                event(
                        server("skyblock-1"),
                        false
                );

        Component expectedReason =
                event.getServerKickReason().orElseThrow();

        listener.onKickedFromServer(event);

        KickedFromServerEvent.DisconnectPlayer result =
                assertInstanceOf(
                        KickedFromServerEvent.DisconnectPlayer.class,
                        event.getResult()
                );

        assertSame(
                expectedReason,
                result.getReasonComponent()
        );
    }
    @Test
    void disconnectsWhenLobbyFallbackIsCurrentServer() {
        TransferTargetResolver targetResolver =
                mock(TransferTargetResolver.class);
        RegisteredServer currentLobby =
                server("lobby-1");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.notAuthenticated()
        );

        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(currentLobby)
        );

        BackendKickFailoverListener listener =
                listener(targetResolver);

        KickedFromServerEvent event =
                event(
                        player("lobby-1"),
                        server("skyblock-1"),
                        false
                );

        listener.onKickedFromServer(event);

        assertInstanceOf(
                KickedFromServerEvent.DisconnectPlayer.class,
                event.getResult()
        );
    }
    @Test
    void secondKickBeforeSuccessfulConnectionPreservesOriginalResult() {
        TransferTargetResolver targetResolver =
                mock(TransferTargetResolver.class);
        RegisteredServer target =
                server("skyblock-2");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(target)
        );

        BackendKickFailoverListener listener =
                listener(targetResolver);

        listener.onKickedFromServer(
                event(
                        server("skyblock-1"),
                        false
                )
        );

        KickedFromServerEvent secondEvent =
                event(
                        server("skyblock-1"),
                        false
                );

        KickedFromServerEvent.ServerKickResult originalResult =
                secondEvent.getResult();

        listener.onKickedFromServer(secondEvent);

        assertSame(
                originalResult,
                secondEvent.getResult()
        );
    }

    @Test
    void serverConnectedEventClearsReservation() {
        TransferTargetResolver targetResolver =
                mock(TransferTargetResolver.class);
        RegisteredServer firstTarget =
                server("skyblock-2");
        RegisteredServer secondTarget =
                server("skyblock-3");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(firstTarget),
                TransferTargetResolution.resolved(secondTarget)
        );

        BackendKickFailoverListener listener =
                listener(targetResolver);

        listener.onKickedFromServer(
                event(
                        server("skyblock-1"),
                        false
                )
        );

        listener.onServerConnected(
                new ServerConnectedEvent(
                        player(),
                        firstTarget,
                        server("skyblock-1")
                )
        );

        KickedFromServerEvent secondEvent =
                event(
                        server("skyblock-1"),
                        false
                );

        listener.onKickedFromServer(secondEvent);

        KickedFromServerEvent.RedirectPlayer result =
                assertInstanceOf(
                        KickedFromServerEvent.RedirectPlayer.class,
                        secondEvent.getResult()
                );

        assertSame(
                secondTarget,
                result.getServer()
        );
    }

    @Test
    void disconnectEventClearsReservation() {
        TransferTargetResolver targetResolver =
                mock(TransferTargetResolver.class);
        RegisteredServer firstTarget =
                server("skyblock-2");
        RegisteredServer secondTarget =
                server("skyblock-3");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(firstTarget),
                TransferTargetResolution.resolved(secondTarget)
        );

        BackendKickFailoverListener listener =
                listener(targetResolver);

        listener.onKickedFromServer(
                event(
                        server("skyblock-1"),
                        false
                )
        );

        listener.onDisconnect(
                new DisconnectEvent(
                        player(),
                        DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
                )
        );

        KickedFromServerEvent secondEvent =
                event(
                        server("skyblock-1"),
                        false
                );

        listener.onKickedFromServer(secondEvent);

        KickedFromServerEvent.RedirectPlayer result =
                assertInstanceOf(
                        KickedFromServerEvent.RedirectPlayer.class,
                        secondEvent.getResult()
                );

        assertSame(
                secondTarget,
                result.getServer()
        );
    }

    @Test
    void disconnectEventCancelsColdBootstrapReservation() {
        TransferTargetResolver targetResolver =
                mock(TransferTargetResolver.class);
        BackendBootstrapRegistry bootstrapRegistry =
                new BackendBootstrapRegistry();
        RegisteredServer coldLobby =
                server("lobby-1");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.notConfigured()
        );

        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.bootstrapRequired(
                        coldLobby
                )
        );

        BackendKickFailoverListener listener =
                listener(
                        targetResolver,
                        bootstrapRegistry
                );

        listener.onKickedFromServer(
                event(
                        server("skyblock-1"),
                        false
                )
        );

        assertTrue(
                bootstrapRegistry
                        .findByTarget("lobby-1")
                        .isPresent()
        );

        listener.onDisconnect(
                new DisconnectEvent(
                        player(),
                        DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
                )
        );

        assertTrue(
                bootstrapRegistry
                        .findByTarget("lobby-1")
                        .isEmpty()
        );
    }

    @Test
    void rejectsNullDependencyAndEvent() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverListener(null)
        );

        BackendKickFailoverListener listener =
                listener(mock(TransferTargetResolver.class));

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

    private BackendKickFailoverListener listener(
            TransferTargetResolver targetResolver
    ) {
        return listener(
                targetResolver,
                new BackendBootstrapRegistry()
        );
    }

    private BackendKickFailoverListener listener(
            TransferTargetResolver targetResolver,
            BackendBootstrapRegistry bootstrapRegistry
    ) {
        return new BackendKickFailoverListener(
                new BackendKickFailoverService(
                        authenticatedSessions(),
                        identityRegistry(),
                        targetResolver,
                        bootstrapRegistry,
                        new PendingPlayerFailoverRegistry()
                )
        );
    }

    private AuthenticatedPlayerSessionRegistry authenticatedSessions() {
        AuthenticatedPlayerSessionRegistry registry =
                new AuthenticatedPlayerSessionRegistry();

        registry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                )
        );

        return registry;
    }

    private BackendIdentityRegistry identityRegistry() {
        BackendIdentityRegistry registry =
                new BackendIdentityRegistry();

        registry.register(
                new BackendIdentity(
                        "skyblock-1",
                        BackendType.SKYBLOCK
                )
        );

        return registry;
    }

    private KickedFromServerEvent event(
            RegisteredServer server,
            boolean kickedDuringConnect
    ) {
        return new KickedFromServerEvent(
                player(),
                server,
                Component.text("Sin conexion con el backend."),
                kickedDuringConnect,
                KickedFromServerEvent.Notify.create(
                        Component.text("Destino no disponible.")
                )
        );
    }

    private KickedFromServerEvent event(
            Player player,
            RegisteredServer server,
            boolean kickedDuringConnect
    ) {
        return new KickedFromServerEvent(
                player,
                server,
                Component.text("Sin conexion con el backend."),
                kickedDuringConnect,
                KickedFromServerEvent.Notify.create(
                        Component.text("Destino no disponible.")
                )
        );
    }

    private Player player() {
        Player player =
                mock(Player.class);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getCurrentServer())
                .thenReturn(Optional.empty());

        return player;
    }

    private Player player(String currentServerName) {
        Player player =
                mock(Player.class);
        ServerConnection connection =
                mock(ServerConnection.class);
        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getCurrentServer())
                .thenReturn(Optional.of(connection));
        when(connection.getServerInfo())
                .thenReturn(serverInfo);
        when(serverInfo.getName())
                .thenReturn(currentServerName);

        return player;
    }

    private RegisteredServer server(String serverName) {
        RegisteredServer server =
                mock(RegisteredServer.class);

        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(server.getServerInfo())
                .thenReturn(serverInfo);

        when(serverInfo.getName())
                .thenReturn(serverName);

        return server;
    }
}

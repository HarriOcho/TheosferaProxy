package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendKickFailoverServiceTest {

    private static final UUID PLAYER_ID = UUID.fromString(
            "5b8578de-76a6-467f-9211-399b3dfe723a"
    );
    private static final Component REASON =
            Component.text("Sin conexion con el backend.");

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private BackendAuthorizationPolicy authorizationPolicy;
    private DistributedBackendKickFailoverCoordinator coordinator;
    private BackendKickFailoverService service;
    private Player player;

    @BeforeEach
    void setUp() {
        sessionRegistry = new AuthenticatedPlayerSessionRegistry();
        authorizationPolicy = policy();
        coordinator = mock(DistributedBackendKickFailoverCoordinator.class);
        player = player(Optional.empty());
        service = new BackendKickFailoverService(
                sessionRegistry,
                authorizationPolicy,
                coordinator
        );
    }

    @Test
    void ignoresKickDuringServerConnect() {
        authenticatePlayer();

        BackendKickFailoverResolution result = resolve(
                event(player, server("skyblock-1"), true)
        );

        assertSame(BackendKickFailoverResolutionStatus.IGNORED, result.status());
        verify(coordinator, never()).resolve(any(), any(), any(), any());
    }

    @Test
    void ignoresUnauthenticatedPlayer() {
        BackendKickFailoverResolution result = resolve(
                event(player, server("skyblock-1"), false)
        );

        assertSame(BackendKickFailoverResolutionStatus.IGNORED, result.status());
        verify(coordinator, never()).resolve(any(), any(), any(), any());
    }

    @Test
    void disconnectsBackendMissingFromPolicy() {
        authenticatePlayer();

        BackendKickFailoverResolution result = resolve(
                event(player, server("unknown-1"), false)
        );

        assertSame(BackendKickFailoverResolutionStatus.DISCONNECT, result.status());
        assertSame(REASON, result.reason().orElseThrow());
        verify(coordinator, never()).resolve(any(), any(), any(), any());
    }

    @Test
    void disconnectsAuthBackendWithoutDistributedAllocation() {
        authenticatePlayer();

        BackendKickFailoverResolution result = resolve(
                event(player, server("auth-1"), false)
        );

        assertSame(BackendKickFailoverResolutionStatus.DISCONNECT, result.status());
        verify(coordinator, never()).resolve(any(), any(), any(), any());
    }

    @Test
    void delegatesKickAfterSourceControlIdentityHasAlreadyDisappeared() {
        authenticatePlayer();
        RegisteredServer target = server("skyblock-2");
        BackendKickFailoverResolution expected =
                BackendKickFailoverResolution.redirect(target);

        when(coordinator.resolve(
                eq(player),
                eq(BackendType.SKYBLOCK),
                eq(Set.of("skyblock-1")),
                eq(REASON)
        )).thenReturn(CompletableFuture.completedFuture(expected));

        BackendKickFailoverResolution result = resolve(
                event(player, server("skyblock-1"), false)
        );

        assertSame(expected, result);
        verify(coordinator).resolve(
                player,
                BackendType.SKYBLOCK,
                Set.of("skyblock-1"),
                REASON
        );
    }

    @Test
    void delegatesAuthenticatedKickWithFailedAndCurrentServerExcluded() {
        authenticatePlayer();
        Player playerOnLobby = player(Optional.of("lobby-1"));
        RegisteredServer target = server("skyblock-2");
        BackendKickFailoverResolution expected =
                BackendKickFailoverResolution.redirect(target);

        when(coordinator.resolve(
                eq(playerOnLobby),
                eq(BackendType.SKYBLOCK),
                eq(Set.of("skyblock-1", "lobby-1")),
                eq(REASON)
        )).thenReturn(CompletableFuture.completedFuture(expected));

        BackendKickFailoverResolution result = resolve(
                event(playerOnLobby, server("skyblock-1"), false)
        );

        assertSame(expected, result);
        verify(coordinator).resolve(
                playerOnLobby,
                BackendType.SKYBLOCK,
                Set.of("skyblock-1", "lobby-1"),
                REASON
        );
    }

    @Test
    void coordinatorFailureDisconnectsWithOriginalReason() {
        authenticatePlayer();

        when(coordinator.resolve(
                eq(player),
                eq(BackendType.SKYBLOCK),
                eq(Set.of("skyblock-1")),
                eq(REASON)
        )).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("redis unavailable")
        ));

        BackendKickFailoverResolution result = resolve(
                event(player, server("skyblock-1"), false)
        );

        assertSame(BackendKickFailoverResolutionStatus.DISCONNECT, result.status());
        assertSame(REASON, result.reason().orElseThrow());
    }

    @Test
    void nullCoordinatorStageDisconnectsWithOriginalReason() {
        authenticatePlayer();

        when(coordinator.resolve(
                eq(player),
                eq(BackendType.SKYBLOCK),
                eq(Set.of("skyblock-1")),
                eq(REASON)
        )).thenReturn(null);

        BackendKickFailoverResolution result = resolve(
                event(player, server("skyblock-1"), false)
        );

        assertSame(BackendKickFailoverResolutionStatus.DISCONNECT, result.status());
        assertSame(REASON, result.reason().orElseThrow());
    }

    @Test
    void successfulConnectionDelegatesBackendName() {
        service.completeSuccessfulConnection(PLAYER_ID, "skyblock-2");

        verify(coordinator).completeSuccessfulConnection(
                PLAYER_ID,
                "skyblock-2"
        );
    }

    @Test
    void disconnectDelegatesPendingCleanup() {
        service.cancelPendingFailover(PLAYER_ID);

        verify(coordinator).cancelPendingFailover(PLAYER_ID);
    }

    @Test
    void rejectsNullDependenciesAndEvent() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverService(
                        null,
                        authorizationPolicy,
                        coordinator
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverService(
                        sessionRegistry,
                        null,
                        coordinator
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverService(
                        sessionRegistry,
                        authorizationPolicy,
                        null
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> service.resolveFailoverTarget(null)
        );
    }

    private BackendKickFailoverResolution resolve(KickedFromServerEvent event) {
        return service.resolveFailoverTarget(event)
                .toCompletableFuture()
                .join();
    }

    private void authenticatePlayer() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                )
        );
    }

    private BackendAuthorizationPolicy policy() {
        return new BackendAuthorizationPolicy(
                Map.of(
                        "auth-1",
                        new BackendPolicyEntry(
                                BackendType.AUTH,
                                1,
                                100
                        ),
                        "skyblock-1",
                        new BackendPolicyEntry(
                                BackendType.SKYBLOCK,
                                200,
                                80
                        )
                )
        );
    }

    private KickedFromServerEvent event(
            Player eventPlayer,
            RegisteredServer failedServer,
            boolean kickedDuringConnect
    ) {
        return new KickedFromServerEvent(
                eventPlayer,
                failedServer,
                REASON,
                kickedDuringConnect,
                KickedFromServerEvent.Notify.create(
                        Component.text("Destino no disponible.")
                )
        );
    }

    private Player player(Optional<String> currentServerName) {
        Player result = mock(Player.class);
        when(result.getUniqueId()).thenReturn(PLAYER_ID);

        if (currentServerName.isEmpty()) {
            when(result.getCurrentServer()).thenReturn(Optional.empty());
            return result;
        }

        ServerConnection connection = mock(ServerConnection.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn(currentServerName.orElseThrow());
        when(connection.getServerInfo()).thenReturn(info);
        when(result.getCurrentServer()).thenReturn(Optional.of(connection));
        return result;
    }

    private RegisteredServer server(String serverName) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(serverName);
        return server;
    }
}

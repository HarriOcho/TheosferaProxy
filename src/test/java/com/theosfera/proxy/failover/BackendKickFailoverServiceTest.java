package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendKickFailoverServiceTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "5b8578de-76a6-467f-9211-399b3dfe723a"
            );

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private BackendIdentityRegistry identityRegistry;
    private TransferTargetResolver targetResolver;
    private PendingPlayerFailoverRegistry failoverRegistry;
    private BackendKickFailoverService service;
    private Player player;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();
        identityRegistry =
                new BackendIdentityRegistry();
        targetResolver =
                mock(TransferTargetResolver.class);
        failoverRegistry =
                new PendingPlayerFailoverRegistry();
        player = mock(Player.class);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getCurrentServer()).thenReturn(Optional.empty());

        service =
                new BackendKickFailoverService(
                        sessionRegistry,
                        identityRegistry,
                        targetResolver,
                        failoverRegistry
                );
    }

    @Test
    void ignoresKickDuringServerConnect() {
        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                true
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.IGNORED,
                result.status()
        );

        verify(
                targetResolver,
                never()
        ).resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        );
    }

    @Test
    void ignoresUnauthenticatedPlayer() {
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.IGNORED,
                result.status()
        );

        verify(
                targetResolver,
                never()
        ).resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        );
    }

    @Test
    void ignoresBackendWithoutIdentity() {
        authenticatePlayer();

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );

        verify(
                targetResolver,
                never()
        ).resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        );
    }

    @Test
    void ignoresIdentityWithDifferentServerName() {
        BackendIdentityRegistry mismatchedRegistry =
                mock(BackendIdentityRegistry.class);

        when(mismatchedRegistry.find("skyblock-1"))
                .thenReturn(
                        Optional.of(
                                new BackendIdentity(
                                        "skyblock-2",
                                        BackendType.SKYBLOCK
                                )
                        )
                );

        BackendKickFailoverService mismatchedService =
                new BackendKickFailoverService(
                        sessionRegistry,
                        mismatchedRegistry,
                        targetResolver,
                        failoverRegistry
                );

        authenticatePlayer();

        BackendKickFailoverResolution result =
                mismatchedService.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );

        verify(
                targetResolver,
                never()
        ).resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        );
    }

    @Test
    void ignoresAuthBackend() {
        authenticatePlayer();
        registerIdentity(
                "auth-1",
                BackendType.AUTH
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("auth-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );

        verify(
                targetResolver,
                never()
        ).resolve(
                BackendType.AUTH,
                Set.of("auth-1")
        );
    }

    @Test
    void resolvesAnotherResolvedBackendOfSameType() {
        RegisteredServer target =
                server("skyblock-2");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(target)
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.REDIRECT,
                result.status()
        );


        assertSame(
                target,
                result.redirectTarget().orElseThrow()
        );

        assertTrue(
                failoverRegistry.isReserved(PLAYER_ID)
        );

        verify(targetResolver).resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        );
    }

    @Test
    void secondKickBeforeSuccessfulConnectionKeepsOriginalFlow() {
        RegisteredServer target =
                server("skyblock-2");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(target)
        );

        BackendKickFailoverResolution firstResult =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        BackendKickFailoverResolution secondResult =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.REDIRECT,
                firstResult.status()
        );


        assertSame(
                target,
                firstResult.redirectTarget().orElseThrow()
        );

        assertSame(
                BackendKickFailoverResolutionStatus.IGNORED,
                secondResult.status()
        );

        verify(targetResolver, times(1)).resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        );
    }

    @Test
    void successfulConnectionClearsPendingFailover() {
        failoverRegistry.reserve(PLAYER_ID);

        service.clearPendingFailover(PLAYER_ID);

        assertTrue(
                !failoverRegistry.isReserved(PLAYER_ID)
        );
    }

    @Test
    void allowsNewFailoverChainAfterSuccessfulConnection() {
        RegisteredServer firstTarget =
                server("skyblock-2");
        RegisteredServer secondTarget =
                server("skyblock-3");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(firstTarget),
                TransferTargetResolution.resolved(secondTarget)
        );

        BackendKickFailoverResolution firstResult =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        service.clearPendingFailover(PLAYER_ID);

        BackendKickFailoverResolution secondResult =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.REDIRECT,
                firstResult.status()
        );


        assertSame(
                firstTarget,
                firstResult.redirectTarget().orElseThrow()
        );

        assertSame(
                BackendKickFailoverResolutionStatus.REDIRECT,
                secondResult.status()
        );


        assertSame(
                secondTarget,
                secondResult.redirectTarget().orElseThrow()
        );

        verify(targetResolver, times(2)).resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        );
    }

    @Test
    void doesNotAcceptSameTypeBootstrapTarget() {
        RegisteredServer coldTarget =
                server("lobby-2");

        authenticatePlayer();
        registerIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(
                TransferTargetResolution.bootstrapRequired(coldTarget)
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("lobby-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );
    }

    @Test
    void doesNotReserveWhenNoSafeTargetExists() {
        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

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
                TransferTargetResolution.notConfigured()
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );
        assertTrue(
                !failoverRegistry.isReserved(PLAYER_ID)
        );
    }

    @Test
    void usesResolvedLobbyFallbackForSkyblock() {
        RegisteredServer lobby =
                server("lobby-1");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

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
                TransferTargetResolution.resolved(lobby)
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.REDIRECT,
                result.status()
        );


        assertSame(
                lobby,
                result.redirectTarget().orElseThrow()
        );
    }

    @Test
    void rejectsLobbyFallbackWhenItIsCurrentServer() {
        RegisteredServer currentLobby =
                server("lobby-1");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );
        setCurrentServer("lobby-1");

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

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );
        assertTrue(
                !failoverRegistry.isReserved(PLAYER_ID)
        );
    }

    @Test
    void rejectsSameTypeTargetWhenItIsCurrentServer() {
        RegisteredServer currentSkyblock =
                server("skyblock-2");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );
        setCurrentServer("skyblock-2");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(currentSkyblock)
        );

        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.notConfigured()
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );
        assertTrue(
                !failoverRegistry.isReserved(PLAYER_ID)
        );
    }

    @Test
    void acceptsTargetDifferentFromCurrentServer() {
        RegisteredServer target =
                server("skyblock-2");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );
        setCurrentServer("lobby-1");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(target)
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.REDIRECT,
                result.status()
        );


        assertSame(
                target,
                result.redirectTarget().orElseThrow()
        );
        assertTrue(
                failoverRegistry.isReserved(PLAYER_ID)
        );
    }

    @Test
    void doesNotAttemptLobbyFallbackWhenSourceIsLobby() {
        authenticatePlayer();
        registerIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(
                TransferTargetResolution.notConfigured()
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("lobby-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );

        verify(targetResolver, times(1)).resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
    }

    @Test
    void doesNotAcceptLobbyBootstrapFallback() {
        RegisteredServer lobby =
                server("lobby-1");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

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
                TransferTargetResolution.bootstrapRequired(lobby)
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                server("skyblock-1"),
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );
    }

    @Test
    void neverReturnsFailedServerAsTarget() {
        RegisteredServer failed =
                server("skyblock-1");

        authenticatePlayer();
        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(failed)
        );

        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(failed)
        );

        BackendKickFailoverResolution result =
                service.resolveFailoverTarget(
                        event(
                                failed,
                                false
                        )
                );

        assertSame(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                result.status()
        );
    }

    @Test
    void rejectsNullDependenciesAndEvent() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverService(
                        null,
                        identityRegistry,
                        targetResolver,
                        failoverRegistry
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverService(
                        sessionRegistry,
                        null,
                        targetResolver,
                        failoverRegistry
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverService(
                        sessionRegistry,
                        identityRegistry,
                        null,
                        failoverRegistry
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new BackendKickFailoverService(
                        sessionRegistry,
                        identityRegistry,
                        targetResolver,
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> service.resolveFailoverTarget(null)
        );
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

    private void registerIdentity(
            String serverName,
            BackendType backendType
    ) {
        identityRegistry.register(
                new BackendIdentity(
                        serverName,
                        backendType
                )
        );
    }

    private KickedFromServerEvent event(
            RegisteredServer server,
            boolean kickedDuringConnect
    ) {
        return new KickedFromServerEvent(
                player,
                server,
                Component.text("Sin conexi\u00f3n con el backend."),
                kickedDuringConnect,
                KickedFromServerEvent.Notify.create(
                        Component.text("Destino no disponible.")
                )
        );
    }

    private void setCurrentServer(String serverName) {
        ServerConnection connection =
                mock(ServerConnection.class);
        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(player.getCurrentServer())
                .thenReturn(Optional.of(connection));

        when(connection.getServerInfo())
                .thenReturn(serverInfo);

        when(serverInfo.getName())
                .thenReturn(serverName);
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

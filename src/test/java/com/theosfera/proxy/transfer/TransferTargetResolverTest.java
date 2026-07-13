package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferTargetResolverTest {

    private ProxyServer proxyServer;
    private BackendIdentityRegistry identityRegistry;
    private TransferTargetResolver resolver;
    private RegisteredServer skyblockServer;

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);
        identityRegistry = new BackendIdentityRegistry();

        BackendAuthorizationPolicy authorizationPolicy =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "auth-1",
                                BackendType.AUTH,
                                "lobby-1",
                                BackendType.LOBBY,
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                );

        skyblockServer =
                registeredServer("skyblock-1");

        when(proxyServer.getServer("skyblock-1"))
                .thenReturn(Optional.of(skyblockServer));

        when(skyblockServer.getPlayersConnected())
                .thenReturn(List.of());

        resolver = new TransferTargetResolver(
                proxyServer,
                authorizationPolicy,
                identityRegistry
        );
    }

    @Test
    void resolvesConfiguredAndAuthenticatedTarget() {
        identityRegistry.register(
                new BackendIdentity(
                        "skyblock-1",
                        BackendType.SKYBLOCK
                )
        );

        TransferTargetResolution resolution =
                resolver.resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus.RESOLVED,
                resolution.status()
        );

        assertSame(
                skyblockServer,
                resolution
                        .resolvedTarget()
                        .orElseThrow()
        );

        assertFalse(resolution.requiresBootstrap());
    }

    @Test
    void requestsBootstrapForConfiguredEmptyTarget() {
        TransferTargetResolution resolution =
                resolver.resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus
                        .BOOTSTRAP_REQUIRED,
                resolution.status()
        );

        assertSame(
                skyblockServer,
                resolution
                        .resolvedTarget()
                        .orElseThrow()
        );

        assertTrue(resolution.requiresBootstrap());
    }

    @Test
    void rejectsUnauthenticatedTargetWithPlayers() {
        when(skyblockServer.getPlayersConnected())
                .thenReturn(
                        List.of(mock(Player.class))
                );

        TransferTargetResolution resolution =
                resolver.resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus
                        .NOT_AUTHENTICATED,
                resolution.status()
        );

        assertTrue(
                resolution.resolvedTarget().isEmpty()
        );
    }

    @Test
    void rejectsTargetWithConflictingIdentity() {
        identityRegistry.register(
                new BackendIdentity(
                        "skyblock-1",
                        BackendType.LOBBY
                )
        );

        TransferTargetResolution resolution =
                resolver.resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus
                        .NOT_AUTHENTICATED,
                resolution.status()
        );

        assertTrue(
                resolution.resolvedTarget().isEmpty()
        );
    }

    @Test
    void reportsTargetMissingFromVelocityConfiguration() {
        when(proxyServer.getServer("skyblock-1"))
                .thenReturn(Optional.empty());

        TransferTargetResolution resolution =
                resolver.resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus
                        .NOT_CONFIGURED,
                resolution.status()
        );
    }

    @Test
    void refusesAuthAsTransferTarget() {
        TransferTargetResolution resolution =
                resolver.resolve(BackendType.AUTH);

        assertEquals(
                TransferTargetResolutionStatus
                        .NOT_CONFIGURED,
                resolution.status()
        );
    }

    @Test
    void rejectsNullDependenciesAndInput() {
        BackendAuthorizationPolicy authorizationPolicy =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                );

        assertThrows(
                NullPointerException.class,
                () -> new TransferTargetResolver(
                        null,
                        authorizationPolicy,
                        identityRegistry
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new TransferTargetResolver(
                        proxyServer,
                        null,
                        identityRegistry
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new TransferTargetResolver(
                        proxyServer,
                        authorizationPolicy,
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> resolver.resolve(null)
        );
    }

    private RegisteredServer registeredServer(
            String serverName
    ) {
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

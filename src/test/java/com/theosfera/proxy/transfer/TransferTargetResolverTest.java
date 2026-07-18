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

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);
        identityRegistry = new BackendIdentityRegistry();
    }

    @Test
    void resolvesAuthenticatedTargetWithConnectedCarrier() {
        RegisteredServer server =
                configuredServer(
                        "skyblock-1",
                        true
                );

        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                ).resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus.RESOLVED,
                resolution.status()
        );

        assertSame(
                server,
                resolution
                        .resolvedTarget()
                        .orElseThrow()
        );

        assertFalse(resolution.requiresBootstrap());
    }

    @Test
    void requestsBootstrapForEmptyTargetWithMatchingHistoricalIdentity() {
        RegisteredServer server =
                configuredServer(
                        "skyblock-1",
                        false
                );

        registerIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                ).resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus
                        .BOOTSTRAP_REQUIRED,
                resolution.status()
        );

        assertSame(
                server,
                resolution
                        .resolvedTarget()
                        .orElseThrow()
        );

        assertTrue(resolution.requiresBootstrap());
    }

    @Test
    void requestsBootstrapForConfiguredEmptyTargetWithoutIdentity() {
        RegisteredServer server =
                configuredServer(
                        "skyblock-1",
                        false
                );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                ).resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus
                        .BOOTSTRAP_REQUIRED,
                resolution.status()
        );

        assertSame(
                server,
                resolution
                        .resolvedTarget()
                        .orElseThrow()
        );
    }

    @Test
    void rejectsUnauthenticatedTargetWithPlayers() {
        configuredServer(
                "skyblock-1",
                true
        );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                ).resolve(BackendType.SKYBLOCK);

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
    void rejectsEmptyTargetWithConflictingIdentity() {
        configuredServer(
                "skyblock-1",
                false
        );

        registerIdentity(
                "skyblock-1",
                BackendType.LOBBY
        );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                ).resolve(BackendType.SKYBLOCK);

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
    void rejectsOccupiedTargetWithConflictingIdentity() {
        configuredServer(
                "skyblock-1",
                true
        );

        registerIdentity(
                "skyblock-1",
                BackendType.LOBBY
        );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                ).resolve(BackendType.SKYBLOCK);

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
    void prefersAuthenticatedCarrierOverAlphabeticallyEarlierColdTarget() {
        RegisteredServer coldServer =
                configuredServer(
                        "lobby-a",
                        false
                );

        RegisteredServer authenticatedServer =
                configuredServer(
                        "lobby-b",
                        true
                );

        registerIdentity(
                "lobby-b",
                BackendType.LOBBY
        );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "lobby-a",
                                BackendType.LOBBY,
                                "lobby-b",
                                BackendType.LOBBY
                        )
                ).resolve(BackendType.LOBBY);

        assertEquals(
                TransferTargetResolutionStatus.RESOLVED,
                resolution.status()
        );

        assertSame(
                authenticatedServer,
                resolution
                        .resolvedTarget()
                        .orElseThrow()
        );

        assertFalse(
                coldServer.equals(
                        resolution.resolvedTarget().orElseThrow()
                )
        );
    }

    @Test
    void selectsAuthenticatedTargetsDeterministicallyByName() {
        RegisteredServer first =
                configuredServer(
                        "lobby-a",
                        true
                );

        configuredServer(
                "lobby-b",
                true
        );

        registerIdentity(
                "lobby-a",
                BackendType.LOBBY
        );

        registerIdentity(
                "lobby-b",
                BackendType.LOBBY
        );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "lobby-b",
                                BackendType.LOBBY,
                                "lobby-a",
                                BackendType.LOBBY
                        )
                ).resolve(BackendType.LOBBY);

        assertEquals(
                TransferTargetResolutionStatus.RESOLVED,
                resolution.status()
        );

        assertSame(
                first,
                resolution
                        .resolvedTarget()
                        .orElseThrow()
        );
    }

    @Test
    void selectsColdTargetsDeterministicallyByName() {
        RegisteredServer first =
                configuredServer(
                        "lobby-a",
                        false
                );

        configuredServer(
                "lobby-b",
                false
        );

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "lobby-b",
                                BackendType.LOBBY,
                                "lobby-a",
                                BackendType.LOBBY
                        )
                ).resolve(BackendType.LOBBY);

        assertEquals(
                TransferTargetResolutionStatus
                        .BOOTSTRAP_REQUIRED,
                resolution.status()
        );

        assertSame(
                first,
                resolution
                        .resolvedTarget()
                        .orElseThrow()
        );
    }

    @Test
    void reportsTargetMissingFromVelocityConfiguration() {
        when(proxyServer.getServer("skyblock-1"))
                .thenReturn(Optional.empty());

        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                ).resolve(BackendType.SKYBLOCK);

        assertEquals(
                TransferTargetResolutionStatus
                        .NOT_CONFIGURED,
                resolution.status()
        );
    }

    @Test
    void refusesAuthAsTransferTarget() {
        TransferTargetResolution resolution =
                resolverFor(
                        Map.of(
                                "auth-1",
                                BackendType.AUTH
                        )
                ).resolve(BackendType.AUTH);

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
                () -> resolverFor(
                        Map.of(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                ).resolve(null)
        );
    }

    private TransferTargetResolver resolverFor(
            Map<String, BackendType> allowedBackends
    ) {
        return new TransferTargetResolver(
                proxyServer,
                new BackendAuthorizationPolicy(
                        allowedBackends
                ),
                identityRegistry
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

    private RegisteredServer configuredServer(
            String serverName,
            boolean hasConnectedPlayers
    ) {
        RegisteredServer server =
                registeredServer(serverName);

        when(proxyServer.getServer(serverName))
                .thenReturn(Optional.of(server));

        when(server.getPlayersConnected())
                .thenReturn(
                        hasConnectedPlayers
                                ? List.of(mock(Player.class))
                                : List.of()
                );

        return server;
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

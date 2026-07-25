package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferTargetLoadBalancingTest {

    private ProxyServer proxyServer;
    private BackendIdentityRegistry identityRegistry;
    private BackendHealthRegistry healthRegistry;

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);
        identityRegistry = new BackendIdentityRegistry();
        healthRegistry = new BackendHealthRegistry(
                Clock.fixed(
                        Instant.parse("2026-07-25T04:00:00Z"),
                        ZoneOffset.UTC
                ),
                Duration.ofSeconds(15)
        );
    }

    @Test
    void selectsLowestProportionalLoadAcrossHealthyTargets() {
        configuredServer("lobby-a", 3);
        RegisteredServer selected =
                configuredServer("lobby-b", 4);

        registerHealthy("lobby-a");
        registerHealthy("lobby-b");

        TransferTargetResolution resolution =
                resolver(
                        Map.of(
                                "lobby-a",
                                entry(5, 100),
                                "lobby-b",
                                entry(10, 100)
                        )
                ).resolve(BackendType.LOBBY);

        assertEquals(
                TransferTargetResolutionStatus.RESOLVED,
                resolution.status()
        );
        assertSame(
                selected,
                resolution.resolvedTarget().orElseThrow()
        );
    }

    @Test
    void ignoresFullHealthyTarget() {
        configuredServer("lobby-a", 5);
        RegisteredServer selected =
                configuredServer("lobby-b", 4);

        registerHealthy("lobby-a");
        registerHealthy("lobby-b");

        TransferTargetResolution resolution =
                resolver(
                        Map.of(
                                "lobby-a",
                                entry(5, 100),
                                "lobby-b",
                                entry(5, 100)
                        )
                ).resolve(BackendType.LOBBY);

        assertEquals(
                TransferTargetResolutionStatus.RESOLVED,
                resolution.status()
        );
        assertSame(
                selected,
                resolution.resolvedTarget().orElseThrow()
        );
    }

    @Test
    void usesPreferenceWhenUtilizationIsEqual() {
        configuredServer("lobby-a", 1);
        RegisteredServer selected =
                configuredServer("lobby-b", 2);

        registerHealthy("lobby-a");
        registerHealthy("lobby-b");

        TransferTargetResolution resolution =
                resolver(
                        Map.of(
                                "lobby-a",
                                entry(2, 80),
                                "lobby-b",
                                entry(4, 90)
                        )
                ).resolve(BackendType.LOBBY);

        assertEquals(
                TransferTargetResolutionStatus.RESOLVED,
                resolution.status()
        );
        assertSame(
                selected,
                resolution.resolvedTarget().orElseThrow()
        );
    }

    @Test
    void requestsBootstrapWhenActiveTargetsAreFullAndColdTargetExists() {
        configuredServer("lobby-a", 5);
        RegisteredServer coldTarget =
                configuredServer("lobby-b", 0);

        registerHealthy("lobby-a");

        TransferTargetResolution resolution =
                resolver(
                        Map.of(
                                "lobby-a",
                                entry(5, 100),
                                "lobby-b",
                                entry(5, 90)
                        )
                ).resolve(BackendType.LOBBY);

        assertEquals(
                TransferTargetResolutionStatus
                        .BOOTSTRAP_REQUIRED,
                resolution.status()
        );
        assertSame(
                coldTarget,
                resolution.resolvedTarget().orElseThrow()
        );
        assertTrue(resolution.requiresBootstrap());
    }

    @Test
    void failsClosedWhenOnlyHealthyTargetIsFull() {
        configuredServer("lobby-a", 5);
        registerHealthy("lobby-a");

        TransferTargetResolution resolution =
                resolver(
                        Map.of(
                                "lobby-a",
                                entry(5, 100)
                        )
                ).resolve(BackendType.LOBBY);

        assertEquals(
                TransferTargetResolutionStatus
                        .NO_CAPACITY,
                resolution.status()
        );
        assertTrue(resolution.resolvedTarget().isEmpty());
    }

    private TransferTargetResolver resolver(
            Map<String, BackendPolicyEntry> entries
    ) {
        return new TransferTargetResolver(
                proxyServer,
                new BackendAuthorizationPolicy(entries),
                identityRegistry,
                healthRegistry
        );
    }

    private BackendPolicyEntry entry(
            int capacity,
            int preference
    ) {
        return new BackendPolicyEntry(
                BackendType.LOBBY,
                capacity,
                preference
        );
    }

    private void registerHealthy(String serverName) {
        identityRegistry.register(
                new BackendIdentity(
                        serverName,
                        BackendType.LOBBY
                )
        );
        healthRegistry.markHealthy(serverName);
    }

    private RegisteredServer configuredServer(
            String serverName,
            int connectedPlayers
    ) {
        RegisteredServer server =
                mock(RegisteredServer.class);
        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(proxyServer.getServer(serverName))
                .thenReturn(Optional.of(server));
        when(server.getServerInfo())
                .thenReturn(serverInfo);
        when(serverInfo.getName())
                .thenReturn(serverName);
        when(server.getPlayersConnected())
                .thenReturn(
                        Collections.nCopies(
                                connectedPlayers,
                                mock(Player.class)
                        )
                );

        return server;
    }
}

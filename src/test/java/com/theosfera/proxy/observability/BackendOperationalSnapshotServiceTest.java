package com.theosfera.proxy.observability;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistrationResult;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapReservation;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendOperationalSnapshotServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-25T04:00:00Z");

    @Test
    void capturesConfiguredBackendStateWithoutMutatingRegistries() {
        ProxyServer proxyServer = mock(ProxyServer.class);

        configuredServer(
                proxyServer,
                "lobby-1",
                2
        );

        when(proxyServer.getServer("lobby-2"))
                .thenReturn(Optional.empty());

        BackendAuthorizationPolicy authorizationPolicy =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "lobby-2",
                                entry(10, 80),
                                "lobby-1",
                                entry(5, 90)
                        )
                );

        BackendIdentityRegistry identityRegistry =
                new BackendIdentityRegistry();

        identityRegistry.register(
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        );

        BackendHealthRegistry healthRegistry =
                new BackendHealthRegistry(
                        Clock.fixed(
                                NOW,
                                ZoneOffset.UTC
                        ),
                        Duration.ofSeconds(15)
                );

        healthRegistry.markHealthy("lobby-1");

        BackendBootstrapRegistry bootstrapRegistry =
                new BackendBootstrapRegistry();

        BackendBootstrapReservation bootstrapReservation =
                new BackendBootstrapReservation(
                        "lobby-2",
                        UUID.fromString(
                                "7d022106-1e94-46f3-a3bf-2610705abed7"
                        ),
                        UUID.fromString(
                                "f3126eee-cd50-4e97-b764-dbd5a6dac4b4"
                        ),
                        NOW.toEpochMilli()
                );

        assertEquals(
                BackendBootstrapRegistrationResult.RESERVED,
                bootstrapRegistry.register(
                        bootstrapReservation
                )
        );

        BackendOperationalSnapshotService service =
                new BackendOperationalSnapshotService(
                        proxyServer,
                        authorizationPolicy,
                        identityRegistry,
                        healthRegistry,
                        bootstrapRegistry
                );

        List<BackendOperationalSnapshot> snapshots =
                service.capture();

        assertEquals(2, snapshots.size());
        assertThrows(
                UnsupportedOperationException.class,
                snapshots::clear
        );

        BackendOperationalSnapshot lobbyOne =
                snapshots.get(0);

        assertEquals("lobby-1", lobbyOne.serverName());
        assertEquals(BackendType.LOBBY, lobbyOne.backendType());
        assertEquals(5, lobbyOne.capacity());
        assertEquals(90, lobbyOne.preference());
        assertTrue(lobbyOne.registeredInVelocity());
        assertTrue(lobbyOne.authenticated());
        assertEquals(
                BackendHealthStatus.HEALTHY,
                lobbyOne.healthStatus()
        );
        assertEquals(
                Optional.of(NOW),
                lobbyOne.lastHealthyActivity()
        );
        assertEquals(2, lobbyOne.connectedPlayers());
        assertFalse(
                lobbyOne.bootstrapReservationPresent()
        );

        BackendOperationalSnapshot lobbyTwo =
                snapshots.get(1);

        assertEquals("lobby-2", lobbyTwo.serverName());
        assertFalse(lobbyTwo.registeredInVelocity());
        assertFalse(lobbyTwo.authenticated());
        assertEquals(
                BackendHealthStatus.UNKNOWN,
                lobbyTwo.healthStatus()
        );
        assertTrue(
                lobbyTwo.lastHealthyActivity().isEmpty()
        );
        assertEquals(0, lobbyTwo.connectedPlayers());
        assertTrue(
                lobbyTwo.bootstrapReservationPresent()
        );

        assertEquals(1, identityRegistry.snapshot().size());
        assertEquals(1, healthRegistry.snapshot().size());
        assertEquals(
                1,
                bootstrapRegistry.snapshotByRequest().size()
        );
    }

    @Test
    void doesNotAuthenticateMismatchedBackendIdentity() {
        ProxyServer proxyServer = mock(ProxyServer.class);

        configuredServer(
                proxyServer,
                "lobby-1",
                1
        );

        BackendIdentityRegistry identityRegistry =
                new BackendIdentityRegistry();

        identityRegistry.register(
                new BackendIdentity(
                        "lobby-1",
                        BackendType.SKYBLOCK
                )
        );

        BackendOperationalSnapshotService service =
                new BackendOperationalSnapshotService(
                        proxyServer,
                        new BackendAuthorizationPolicy(
                                Map.of(
                                        "lobby-1",
                                        entry(5, 90)
                                )
                        ),
                        identityRegistry,
                        healthRegistry(),
                        new BackendBootstrapRegistry()
                );

        BackendOperationalSnapshot snapshot =
                service.capture().getFirst();

        assertFalse(snapshot.authenticated());
    }

    private BackendHealthRegistry healthRegistry() {
        return new BackendHealthRegistry(
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                Duration.ofSeconds(15)
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

    private void configuredServer(
            ProxyServer proxyServer,
            String serverName,
            int connectedPlayers
    ) {
        RegisteredServer server =
                mock(RegisteredServer.class);

        when(proxyServer.getServer(serverName))
                .thenReturn(Optional.of(server));

        when(server.getPlayersConnected())
                .thenReturn(
                        Collections.nCopies(
                                connectedPlayers,
                                mock(Player.class)
                        )
                );
    }
}

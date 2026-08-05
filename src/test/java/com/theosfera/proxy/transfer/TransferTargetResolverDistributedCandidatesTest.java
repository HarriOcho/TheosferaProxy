package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferTargetResolverDistributedCandidatesTest {

    @Test
    void distributedCandidatesTreatHealthyAuthenticatedZeroLocalCarrierAsActive() {
        Fixture fixture = fixture(true);

        TransferTargetCandidates candidates = fixture.resolver().candidates(
                BackendType.SKYBLOCK
        );

        assertTrue(candidates.configured());
        assertEquals(1, candidates.activeCandidates().size());
        assertEquals(
                "skyblock-1",
                candidates.activeCandidates().getFirst().serverName()
        );
        assertSame(
                fixture.server(),
                candidates.activeCandidates().getFirst().server()
        );
        assertTrue(candidates.coldCandidates().isEmpty());
    }

    @Test
    void legacyResolveStillTreatsZeroLocalCarrierAsBootstrapRequired() {
        Fixture fixture = fixture(true);

        TransferTargetResolution resolution = fixture.resolver().resolve(
                BackendType.SKYBLOCK
        );

        assertEquals(
                TransferTargetResolutionStatus.BOOTSTRAP_REQUIRED,
                resolution.status()
        );
        assertSame(
                fixture.server(),
                resolution.resolvedTarget().orElseThrow()
        );
    }

    @Test
    void distributedCandidatesKeepUnauthenticatedZeroLocalTargetCold() {
        Fixture fixture = fixture(false);

        TransferTargetCandidates candidates = fixture.resolver().candidates(
                BackendType.SKYBLOCK
        );

        assertTrue(candidates.configured());
        assertTrue(candidates.activeCandidates().isEmpty());
        assertEquals(1, candidates.coldCandidates().size());
        assertSame(
                fixture.server(),
                candidates.coldCandidates().getFirst().server()
        );
    }

    private Fixture fixture(boolean authenticated) {
        ProxyServer proxyServer = mock(ProxyServer.class);
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(server.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn("skyblock-1");
        when(server.getPlayersConnected()).thenReturn(List.of());
        when(proxyServer.getServer("skyblock-1"))
                .thenReturn(Optional.of(server));

        BackendAuthorizationPolicy policy = new BackendAuthorizationPolicy(
                Map.of(
                        "skyblock-1",
                        new BackendPolicyEntry(
                                BackendType.SKYBLOCK,
                                100,
                                80
                        )
                )
        );
        BackendIdentityRegistry identities = new BackendIdentityRegistry();
        BackendHealthRegistry health = new BackendHealthRegistry(
                Clock.fixed(
                        Instant.parse("2026-08-05T03:00:00Z"),
                        ZoneOffset.UTC
                ),
                Duration.ofSeconds(15)
        );

        if (authenticated) {
            identities.register(
                    new BackendIdentity(
                            "skyblock-1",
                            BackendType.SKYBLOCK
                    )
            );
            health.markHealthy("skyblock-1");
        }

        return new Fixture(
                new TransferTargetResolver(
                        proxyServer,
                        policy,
                        identities,
                        health
                ),
                server
        );
    }

    private record Fixture(
            TransferTargetResolver resolver,
            RegisteredServer server
    ) {
    }
}

package com.theosfera.proxy.orchestration;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityProvider;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendReadinessProbeTest {

    @Test
    void requiresConfiguredBackend() {
        BackendReadinessProbe probe = probe(
                Map.of(),
                Map.of(),
                new BackendHealthRegistry(
                        Clock.systemUTC(),
                        Duration.ofSeconds(30)
                )
        );

        assertEquals(
                BackendReadinessStatus.TARGET_NOT_CONFIGURED,
                probe.check("lobby-2").status()
        );
    }

    @Test
    void requiresCurrentControlIdentity() {
        BackendHealthRegistry health = healthRegistry();
        health.markHealthy("lobby-2");

        BackendReadinessSnapshot snapshot = probe(
                policy(),
                Map.of(),
                health
        ).check("lobby-2");

        assertEquals(
                BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED,
                snapshot.status()
        );
        assertTrue(snapshot.observedIdentity().isEmpty());
    }

    @Test
    void rejectsIdentityThatDoesNotMatchStaticPolicy() {
        BackendHealthRegistry health = healthRegistry();
        health.markHealthy("lobby-2");

        BackendReadinessSnapshot snapshot = probe(
                policy(),
                Map.of(
                        "lobby-2",
                        new BackendIdentity(
                                "lobby-2",
                                BackendType.SKYBLOCK
                        )
                ),
                health
        ).check("lobby-2");

        assertEquals(
                BackendReadinessStatus.IDENTITY_MISMATCH,
                snapshot.status()
        );
    }

    @Test
    void authenticatedIdentityStillRequiresFreshHealth() {
        BackendReadinessSnapshot snapshot = probe(
                policy(),
                matchingIdentity(),
                healthRegistry()
        ).check("lobby-2");

        assertEquals(
                BackendReadinessStatus.HEALTH_NOT_READY,
                snapshot.status()
        );
        assertEquals(
                BackendHealthStatus.UNKNOWN,
                snapshot.observedHealthStatus().orElseThrow()
        );
    }

    @Test
    void currentControlIdentityAndHealthyStatusAreReady() {
        BackendHealthRegistry health = healthRegistry();
        health.markHealthy("lobby-2");

        BackendReadinessSnapshot snapshot = probe(
                policy(),
                matchingIdentity(),
                health
        ).check("lobby-2");

        assertEquals(BackendReadinessStatus.READY, snapshot.status());
        assertTrue(snapshot.isReady());
        assertEquals(
                BackendType.LOBBY,
                snapshot.observedIdentity().orElseThrow().backendType()
        );
    }

    private static BackendReadinessProbe probe(
            Map<String, BackendPolicyEntry> policy,
            Map<String, BackendIdentity> identities,
            BackendHealthRegistry healthRegistry
    ) {
        BackendIdentityProvider identityProvider =
                new BackendIdentityProvider() {
                    @Override
                    public Optional<BackendIdentity> find(String serverName) {
                        return Optional.ofNullable(identities.get(serverName));
                    }

                    @Override
                    public Map<String, BackendIdentity> snapshot() {
                        return Map.copyOf(identities);
                    }
                };

        return new BackendReadinessProbe(
                new BackendAuthorizationPolicy(policy),
                identityProvider,
                healthRegistry
        );
    }

    private static Map<String, BackendPolicyEntry> policy() {
        return Map.of(
                "lobby-2",
                new BackendPolicyEntry(BackendType.LOBBY, 100, 80)
        );
    }

    private static Map<String, BackendIdentity> matchingIdentity() {
        return Map.of(
                "lobby-2",
                new BackendIdentity("lobby-2", BackendType.LOBBY)
        );
    }

    private static BackendHealthRegistry healthRegistry() {
        return new BackendHealthRegistry(
                Clock.systemUTC(),
                Duration.ofSeconds(30)
        );
    }
}

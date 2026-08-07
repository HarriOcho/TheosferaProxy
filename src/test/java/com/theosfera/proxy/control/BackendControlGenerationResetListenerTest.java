package com.theosfera.proxy.control;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.PendingBackendPing;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendControlGenerationResetListenerTest {

    @Test
    void newAuthenticatedGenerationInvalidatesOldHealthAndPendingPingFirst() {
        long now = 1_750_000_000_000L;
        Clock clock = Clock.fixed(
                Instant.ofEpochMilli(now),
                ZoneOffset.UTC
        );
        BackendHealthRegistry healthRegistry =
                new BackendHealthRegistry(
                        clock,
                        Duration.ofSeconds(15)
                );
        PendingBackendPingRegistry pendingRegistry =
                new PendingBackendPingRegistry(
                        clock,
                        Duration.ofSeconds(10)
                );
        BackendIdentity identity =
                new BackendIdentity("lobby-2", BackendType.LOBBY);

        healthRegistry.markHealthy("lobby-2");
        pendingRegistry.register(new PendingBackendPing(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"
                ),
                "lobby-2",
                now
        ));

        AtomicBoolean delegateCalled = new AtomicBoolean();
        BackendControlGenerationResetListener listener =
                new BackendControlGenerationResetListener(
                        healthRegistry,
                        pendingRegistry,
                        observed -> {
                            assertEquals(identity, observed);
                            assertEquals(
                                    BackendHealthStatus.UNKNOWN,
                                    healthRegistry.status("lobby-2")
                            );
                            assertFalse(
                                    pendingRegistry.snapshot()
                                            .containsKey("lobby-2")
                            );
                            delegateCalled.set(true);
                        }
                );

        listener.accept(identity);

        assertTrue(delegateCalled.get());
        assertEquals(
                BackendHealthStatus.UNKNOWN,
                healthRegistry.status("lobby-2")
        );
        assertFalse(pendingRegistry.snapshot().containsKey("lobby-2"));
    }
}

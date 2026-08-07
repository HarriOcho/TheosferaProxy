package com.theosfera.proxy.control;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendControlIdentityProviderTest {

    @Test
    void exposesOnlyCurrentAuthenticatedSessionIdentity() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        BackendControlIdentityProvider provider =
                new BackendControlIdentityProvider(registry);
        BackendIdentity identity = new BackendIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        BackendControlSession first = registry.register(
                UUID.randomUUID(),
                identity
        ).current();
        BackendControlSession second = registry.register(
                UUID.randomUUID(),
                identity
        ).current();

        assertEquals(
                identity,
                provider.find("lobby-1").orElseThrow()
        );
        assertFalse(registry.removeIfCurrent(first));
        assertEquals(
                identity,
                provider.find("lobby-1").orElseThrow()
        );

        assertTrue(registry.removeIfCurrent(second));
        assertTrue(provider.find("lobby-1").isEmpty());
    }

    @Test
    void snapshotTracksIndependentCurrentSessions() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        BackendControlIdentityProvider provider =
                new BackendControlIdentityProvider(registry);
        BackendIdentity lobby = new BackendIdentity(
                "lobby-1",
                BackendType.LOBBY
        );
        BackendIdentity skyblock = new BackendIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        registry.register(UUID.randomUUID(), lobby);
        registry.register(UUID.randomUUID(), skyblock);

        assertEquals(
                Map.of(
                        "lobby-1", lobby,
                        "skyblock-1", skyblock
                ),
                provider.snapshot()
        );
    }

    @Test
    void emptyRegistryFailsClosed() {
        BackendControlIdentityProvider provider =
                new BackendControlIdentityProvider(
                        new BackendControlSessionRegistry()
                );

        assertTrue(provider.find("auth-1").isEmpty());
        assertTrue(provider.snapshot().isEmpty());
    }
}

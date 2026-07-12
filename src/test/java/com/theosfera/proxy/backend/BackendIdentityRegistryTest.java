package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendIdentityRegistryTest {

    private final BackendIdentityRegistry registry =
            new BackendIdentityRegistry();

    @Test
    void registersNewIdentity() {
        BackendIdentity identity = new BackendIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        assertEquals(
                BackendRegistrationResult.REGISTERED,
                registry.register(identity)
        );
        assertEquals(
                identity,
                registry.find("lobby-1").orElseThrow()
        );
        assertTrue(registry.isRegistered("lobby-1"));
    }

    @Test
    void treatsIdenticalRegistrationAsIdempotent() {
        BackendIdentity identity = new BackendIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        registry.register(identity);

        assertEquals(
                BackendRegistrationResult.ALREADY_REGISTERED,
                registry.register(identity)
        );
    }

    @Test
    void rejectsConflictingIdentityWithoutReplacingIt() {
        BackendIdentity original = new BackendIdentity(
                "backend-1",
                BackendType.AUTH
        );
        BackendIdentity conflicting = new BackendIdentity(
                "backend-1",
                BackendType.LOBBY
        );

        registry.register(original);

        assertEquals(
                BackendRegistrationResult.CONFLICT,
                registry.register(conflicting)
        );
        assertEquals(
                original,
                registry.find("backend-1").orElseThrow()
        );
    }

    @Test
    void returnsEmptyForUnknownBackend() {
        assertTrue(registry.find("unknown-1").isEmpty());
        assertFalse(registry.isRegistered("unknown-1"));
    }

    @Test
    void exposesImmutableSnapshot() {
        BackendIdentity identity = new BackendIdentity(
                "skyblock-1",
                BackendType.SKYBLOCK
        );

        registry.register(identity);

        Map<String, BackendIdentity> snapshot =
                registry.snapshot();

        assertEquals(identity, snapshot.get("skyblock-1"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.put(
                        "lobby-1",
                        new BackendIdentity(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                )
        );
    }

    @Test
    void snapshotDoesNotChangeAfterNewRegistration() {
        registry.register(
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        );

        Map<String, BackendIdentity> snapshot =
                registry.snapshot();

        registry.register(
                new BackendIdentity(
                        "skyblock-1",
                        BackendType.SKYBLOCK
                )
        );

        assertFalse(snapshot.containsKey("skyblock-1"));
    }

    @Test
    void clearsRegisteredIdentities() {
        registry.register(
                new BackendIdentity(
                        "auth-1",
                        BackendType.AUTH
                )
        );

        registry.clear();

        assertTrue(registry.snapshot().isEmpty());
        assertFalse(registry.isRegistered("auth-1"));
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(
                NullPointerException.class,
                () -> registry.register(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.find(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.isRegistered(null)
        );
    }
}
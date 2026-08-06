package com.theosfera.proxy.control;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendControlSessionRegistryTest {

    @Test
    void registersFirstSessionAsCurrentOwner() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        UUID connectionId = UUID.randomUUID();
        BackendIdentity identity = new BackendIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        BackendControlSessionRegistration registration =
                registry.register(connectionId, identity);

        assertFalse(registration.replacedExistingSession());
        assertEquals(1L, registration.current().generation());
        assertTrue(registry.isCurrent(registration.current()));
        assertEquals(
                registration.current(),
                registry.find("lobby-1").orElseThrow()
        );
    }

    @Test
    void newerAuthenticatedConnectionReplacesPreviousGeneration() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        BackendIdentity identity = new BackendIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        BackendControlSession first = registry.register(
                UUID.randomUUID(),
                identity
        ).current();

        BackendControlSessionRegistration secondRegistration =
                registry.register(
                        UUID.randomUUID(),
                        identity
                );

        assertTrue(secondRegistration.replacedExistingSession());
        assertEquals(
                first,
                secondRegistration.previousOptional().orElseThrow()
        );
        assertTrue(
                secondRegistration.current().generation()
                        > first.generation()
        );
        assertFalse(registry.isCurrent(first));
        assertTrue(registry.isCurrent(secondRegistration.current()));
    }

    @Test
    void rollbackRemovesFirstRegistration() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        BackendControlSessionRegistration registration =
                registry.register(
                        UUID.randomUUID(),
                        new BackendIdentity(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                );

        assertTrue(registry.rollback(registration));
        assertTrue(registry.find("lobby-1").isEmpty());
    }

    @Test
    void rollbackRestoresReplacedPreviousSession() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        BackendIdentity identity = new BackendIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        BackendControlSession first = registry.register(
                UUID.randomUUID(),
                identity
        ).current();
        BackendControlSessionRegistration second = registry.register(
                UUID.randomUUID(),
                identity
        );

        assertTrue(registry.rollback(second));
        assertTrue(registry.isCurrent(first));
        assertEquals(first, registry.find("lobby-1").orElseThrow());
    }

    @Test
    void staleRollbackCannotOverwriteNewerSession() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        BackendIdentity identity = new BackendIdentity(
                "lobby-1",
                BackendType.LOBBY
        );

        BackendControlSessionRegistration first = registry.register(
                UUID.randomUUID(),
                identity
        );
        BackendControlSessionRegistration second = registry.register(
                UUID.randomUUID(),
                identity
        );
        BackendControlSession third = registry.register(
                UUID.randomUUID(),
                identity
        ).current();

        assertFalse(registry.rollback(second));
        assertFalse(registry.rollback(first));
        assertTrue(registry.isCurrent(third));
    }

    @Test
    void staleDisconnectCannotRemoveNewerSession() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
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

        assertFalse(registry.removeIfCurrent(first));
        assertTrue(registry.isCurrent(second));
        assertEquals(
                second,
                registry.find("lobby-1").orElseThrow()
        );
    }

    @Test
    void currentDisconnectRemovesExactSession() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        BackendControlSession session = registry.register(
                UUID.randomUUID(),
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        ).current();

        assertTrue(registry.removeIfCurrent(session));
        assertFalse(registry.isCurrent(session));
        assertTrue(registry.find("lobby-1").isEmpty());
    }

    @Test
    void differentBackendsKeepIndependentSessions() {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();

        BackendControlSession lobby = registry.register(
                UUID.randomUUID(),
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        ).current();
        BackendControlSession skyblock = registry.register(
                UUID.randomUUID(),
                new BackendIdentity(
                        "skyblock-1",
                        BackendType.SKYBLOCK
                )
        ).current();

        assertEquals(2, registry.size());
        assertTrue(registry.isCurrent(lobby));
        assertTrue(registry.isCurrent(skyblock));
    }
}

package com.theosfera.proxy.session;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatedPlayerSessionRegistryTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private final AuthenticatedPlayerSessionRegistry registry =
            new AuthenticatedPlayerSessionRegistry();

    @Test
    void registersNewSession() {
        AuthenticatedPlayerSession session =
                createSession(
                        "HarriOcho",
                        1_750_000_000_000L
                );

        assertEquals(
                PlayerSessionRegistrationResult.REGISTERED,
                registry.register(session)
        );
        assertEquals(
                session,
                registry.find(PLAYER_ID).orElseThrow()
        );
        assertTrue(registry.isAuthenticated(PLAYER_ID));
    }

    @Test
    void treatsIdenticalSessionAsIdempotent() {
        AuthenticatedPlayerSession session =
                createSession(
                        "HarriOcho",
                        1_750_000_000_000L
                );

        registry.register(session);

        assertEquals(
                PlayerSessionRegistrationResult
                        .ALREADY_REGISTERED,
                registry.register(session)
        );
    }

    @Test
    void rejectsConflictingSessionWithoutReplacingIt() {
        AuthenticatedPlayerSession original =
                createSession(
                        "HarriOcho",
                        1_750_000_000_000L
                );
        AuthenticatedPlayerSession conflicting =
                createSession(
                        "HarriOcho",
                        1_750_000_000_025L
                );

        registry.register(original);

        assertEquals(
                PlayerSessionRegistrationResult.CONFLICT,
                registry.register(conflicting)
        );
        assertEquals(
                original,
                registry.find(PLAYER_ID).orElseThrow()
        );
    }

    @Test
    void removesSession() {
        AuthenticatedPlayerSession session =
                createSession(
                        "HarriOcho",
                        1_750_000_000_000L
                );

        registry.register(session);

        assertEquals(
                session,
                registry.remove(PLAYER_ID).orElseThrow()
        );
        assertFalse(registry.isAuthenticated(PLAYER_ID));
    }

    @Test
    void exposesImmutableSnapshot() {
        AuthenticatedPlayerSession session =
                createSession(
                        "HarriOcho",
                        1_750_000_000_000L
                );

        registry.register(session);

        Map<UUID, AuthenticatedPlayerSession> snapshot =
                registry.snapshot();

        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.put(
                        UUID.randomUUID(),
                        session
                )
        );
    }

    @Test
    void clearsSessions() {
        registry.register(
                createSession(
                        "HarriOcho",
                        1_750_000_000_000L
                )
        );

        registry.clear();

        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void rejectsNullInputsAndInvalidSession() {
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
                () -> registry.remove(null)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "invalid-name",
                        1_750_000_000_000L
                )
        );
    }

    private AuthenticatedPlayerSession createSession(
            String playerName,
            long authenticatedAt
    ) {
        return new AuthenticatedPlayerSession(
                PLAYER_ID,
                playerName,
                authenticatedAt
        );
    }
}
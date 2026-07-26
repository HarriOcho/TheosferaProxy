package com.theosfera.proxy.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatedPlayerSessionRegistryExactMatchTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private final AuthenticatedPlayerSessionRegistry registry =
            new AuthenticatedPlayerSessionRegistry();

    @Test
    void removesOnlyExactMatchingSession() {
        AuthenticatedPlayerSession registered =
                session(1_750_000_000_000L);

        AuthenticatedPlayerSession different =
                session(1_750_000_000_025L);

        registry.register(registered);

        assertTrue(
                registry.removeIfMatches(different).isEmpty()
        );
        assertEquals(
                registered,
                registry.find(PLAYER_ID).orElseThrow()
        );

        assertEquals(
                registered,
                registry.removeIfMatches(registered).orElseThrow()
        );
        assertTrue(registry.find(PLAYER_ID).isEmpty());
    }

    @Test
    void rejectsNullExpectedSession() {
        assertThrows(
                NullPointerException.class,
                () -> registry.removeIfMatches(null)
        );
    }

    private AuthenticatedPlayerSession session(
            long authenticatedAt
    ) {
        return new AuthenticatedPlayerSession(
                PLAYER_ID,
                "HarriOcho",
                authenticatedAt
        );
    }
}

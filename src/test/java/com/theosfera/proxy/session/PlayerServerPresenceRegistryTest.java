package com.theosfera.proxy.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerServerPresenceRegistryTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private static final long FIRST_READY_AT =
            1_750_000_000_000L;

    private final AuthenticatedPlayerSessionRegistry
            sessionRegistry =
            new AuthenticatedPlayerSessionRegistry();

    private final PlayerServerPresenceRegistry
            presenceRegistry =
            new PlayerServerPresenceRegistry(
                    sessionRegistry
            );

    @BeforeEach
    void authenticatePlayer() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        FIRST_READY_AT - 100
                )
        );
    }

    @Test
    void recordsInitialPresence() {
        PlayerServerPresence presence =
                createPresence(
                        "lobby-1",
                        FIRST_READY_AT
                );

        assertEquals(
                PlayerPresenceUpdateResult.RECORDED,
                presenceRegistry.update(presence)
        );
        assertEquals(
                presence,
                presenceRegistry.find(
                        PLAYER_ID
                ).orElseThrow()
        );
    }

    @Test
    void treatsIdenticalPresenceAsIdempotent() {
        PlayerServerPresence presence =
                createPresence(
                        "lobby-1",
                        FIRST_READY_AT
                );

        presenceRegistry.update(presence);

        assertEquals(
                PlayerPresenceUpdateResult.ALREADY_RECORDED,
                presenceRegistry.update(presence)
        );
    }

    @Test
    void updatesPresenceWithNewerEvent() {
        presenceRegistry.update(
                createPresence(
                        "lobby-1",
                        FIRST_READY_AT
                )
        );

        PlayerServerPresence skyblockPresence =
                createPresence(
                        "skyblock-1",
                        FIRST_READY_AT + 100
                );

        assertEquals(
                PlayerPresenceUpdateResult.UPDATED,
                presenceRegistry.update(
                        skyblockPresence
                )
        );
        assertEquals(
                skyblockPresence,
                presenceRegistry.find(
                        PLAYER_ID
                ).orElseThrow()
        );
    }

    @Test
    void ignoresStaleEvent() {
        PlayerServerPresence current =
                createPresence(
                        "skyblock-1",
                        FIRST_READY_AT + 100
                );

        presenceRegistry.update(current);

        assertEquals(
                PlayerPresenceUpdateResult.STALE,
                presenceRegistry.update(
                        createPresence(
                                "lobby-1",
                                FIRST_READY_AT
                        )
                )
        );
        assertEquals(
                current,
                presenceRegistry.find(
                        PLAYER_ID
                ).orElseThrow()
        );
    }

    @Test
    void rejectsSameTimestampForDifferentBackend() {
        PlayerServerPresence current =
                createPresence(
                        "lobby-1",
                        FIRST_READY_AT
                );

        presenceRegistry.update(current);

        assertEquals(
                PlayerPresenceUpdateResult.CONFLICT,
                presenceRegistry.update(
                        createPresence(
                                "skyblock-1",
                                FIRST_READY_AT
                        )
                )
        );
        assertEquals(
                current,
                presenceRegistry.find(
                        PLAYER_ID
                ).orElseThrow()
        );
    }

    @Test
    void rejectsPresenceBeforeAuthentication() {
        UUID unknownPlayerId = UUID.randomUUID();

        assertEquals(
                PlayerPresenceUpdateResult.NOT_AUTHENTICATED,
                presenceRegistry.update(
                        new PlayerServerPresence(
                                unknownPlayerId,
                                "lobby-1",
                                FIRST_READY_AT
                        )
                )
        );
        assertTrue(
                presenceRegistry.find(
                        unknownPlayerId
                ).isEmpty()
        );
    }

    @Test
    void removesAndClearsPresence() {
        PlayerServerPresence presence =
                createPresence(
                        "lobby-1",
                        FIRST_READY_AT
                );

        presenceRegistry.update(presence);

        assertEquals(
                presence,
                presenceRegistry.remove(
                        PLAYER_ID
                ).orElseThrow()
        );
        assertFalse(
                presenceRegistry.find(
                        PLAYER_ID
                ).isPresent()
        );

        presenceRegistry.update(presence);
        presenceRegistry.clear();

        assertTrue(presenceRegistry.snapshot().isEmpty());
    }

    @Test
    void rejectsNullDependenciesAndInputs() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerServerPresenceRegistry(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> presenceRegistry.update(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> presenceRegistry.find(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> presenceRegistry.remove(null)
        );
    }

    private PlayerServerPresence createPresence(
            String backendName,
            long readyAt
    ) {
        return new PlayerServerPresence(
                PLAYER_ID,
                backendName,
                readyAt
        );
    }
}
package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BackendLoadSelectorTest {

    private final BackendLoadSelector selector =
            new BackendLoadSelector();

    @Test
    void selectsLowestProportionalLoad() {
        RegisteredServer sixtyPercent =
                mock(RegisteredServer.class);
        RegisteredServer fortyPercent =
                mock(RegisteredServer.class);

        assertSame(
                fortyPercent,
                selector.select(
                        List.of(
                                candidate(
                                        "lobby-a",
                                        sixtyPercent,
                                        3,
                                        5,
                                        100
                                ),
                                candidate(
                                        "lobby-b",
                                        fortyPercent,
                                        4,
                                        10,
                                        100
                                )
                        )
                ).orElseThrow()
        );
    }

    @Test
    void comparesLoadWithoutFloatingPointRounding() {
        RegisteredServer oneThird =
                mock(RegisteredServer.class);
        RegisteredServer twoFifths =
                mock(RegisteredServer.class);

        assertSame(
                oneThird,
                selector.select(
                        List.of(
                                candidate(
                                        "lobby-a",
                                        oneThird,
                                        1,
                                        3,
                                        100
                                ),
                                candidate(
                                        "lobby-b",
                                        twoFifths,
                                        2,
                                        5,
                                        100
                                )
                        )
                ).orElseThrow()
        );
    }

    @Test
    void usesHigherPreferenceForEqualUtilization() {
        RegisteredServer lowerPreference =
                mock(RegisteredServer.class);
        RegisteredServer higherPreference =
                mock(RegisteredServer.class);

        assertSame(
                higherPreference,
                selector.select(
                        List.of(
                                candidate(
                                        "lobby-a",
                                        lowerPreference,
                                        1,
                                        2,
                                        80
                                ),
                                candidate(
                                        "lobby-b",
                                        higherPreference,
                                        2,
                                        4,
                                        90
                                )
                        )
                ).orElseThrow()
        );
    }

    @Test
    void usesServerNameForCompleteTie() {
        RegisteredServer alphabeticallyFirst =
                mock(RegisteredServer.class);
        RegisteredServer alphabeticallySecond =
                mock(RegisteredServer.class);

        assertSame(
                alphabeticallyFirst,
                selector.select(
                        List.of(
                                candidate(
                                        "lobby-b",
                                        alphabeticallySecond,
                                        1,
                                        2,
                                        90
                                ),
                                candidate(
                                        "lobby-a",
                                        alphabeticallyFirst,
                                        1,
                                        2,
                                        90
                                )
                        )
                ).orElseThrow()
        );
    }

    @Test
    void excludesFullAndOverCapacityCandidates() {
        RegisteredServer full =
                mock(RegisteredServer.class);
        RegisteredServer overCapacity =
                mock(RegisteredServer.class);
        RegisteredServer available =
                mock(RegisteredServer.class);

        assertSame(
                available,
                selector.select(
                        List.of(
                                candidate(
                                        "lobby-a",
                                        full,
                                        5,
                                        5,
                                        100
                                ),
                                candidate(
                                        "lobby-b",
                                        overCapacity,
                                        6,
                                        5,
                                        100
                                ),
                                candidate(
                                        "lobby-c",
                                        available,
                                        4,
                                        5,
                                        100
                                )
                        )
                ).orElseThrow()
        );
    }

    @Test
    void returnsEmptyWhenNoCandidateHasCapacity() {
        assertTrue(
                selector.select(
                        List.of(
                                candidate(
                                        "lobby-a",
                                        mock(RegisteredServer.class),
                                        5,
                                        5,
                                        100
                                )
                        )
                ).isEmpty()
        );
    }

    @Test
    void rejectsNullCollectionsAndElements() {
        assertThrows(
                NullPointerException.class,
                () -> selector.select(null)
        );

        List<BackendLoadCandidate> candidates =
                new ArrayList<>();

        candidates.add(null);

        assertThrows(
                NullPointerException.class,
                () -> selector.select(candidates)
        );
    }

    @Test
    void candidateValidatesInputAndCapacityState() {
        RegisteredServer server =
                mock(RegisteredServer.class);

        assertThrows(
                NullPointerException.class,
                () -> new BackendLoadCandidate(
                        null,
                        server,
                        policy(5, 100),
                        1
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendLoadCandidate(
                        " ",
                        server,
                        policy(5, 100),
                        1
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new BackendLoadCandidate(
                        "lobby-a",
                        null,
                        policy(5, 100),
                        1
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new BackendLoadCandidate(
                        "lobby-a",
                        server,
                        null,
                        1
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendLoadCandidate(
                        "lobby-a",
                        server,
                        policy(5, 100),
                        -1
                )
        );

        assertTrue(
                candidate(
                        "lobby-a",
                        server,
                        4,
                        5,
                        100
                ).hasAvailableCapacity()
        );

        assertFalse(
                candidate(
                        "lobby-a",
                        server,
                        5,
                        5,
                        100
                ).hasAvailableCapacity()
        );
    }

    @Test
    void emptyCandidateListReturnsEmpty() {
        assertEquals(
                java.util.Optional.empty(),
                selector.select(List.of())
        );
    }

    private BackendLoadCandidate candidate(
            String serverName,
            RegisteredServer server,
            int connectedPlayers,
            int capacity,
            int preference
    ) {
        return new BackendLoadCandidate(
                serverName,
                server,
                policy(capacity, preference),
                connectedPlayers
        );
    }

    private BackendPolicyEntry policy(
            int capacity,
            int preference
    ) {
        return new BackendPolicyEntry(
                BackendType.LOBBY,
                capacity,
                preference
        );
    }
}

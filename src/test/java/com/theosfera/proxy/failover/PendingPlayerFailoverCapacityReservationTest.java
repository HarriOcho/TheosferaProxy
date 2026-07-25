package com.theosfera.proxy.failover;

import com.theosfera.proxy.transfer.BackendCapacityReservation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingPlayerFailoverCapacityReservationTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "5b8578de-76a6-467f-9211-399b3dfe723a"
            );

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "de4ac295-0a64-4eb3-b7c4-f7439e413032"
            );

    @Test
    void completionReturnsAndClearsCapacityReservation() {
        PendingPlayerFailoverRegistry registry =
                new PendingPlayerFailoverRegistry();

        BackendCapacityReservation reservation =
                reservation(PLAYER_ID);

        assertTrue(
                registry.reserve(
                        PLAYER_ID,
                        reservation
                )
        );

        assertSame(
                reservation,
                registry
                        .clearCapacityForCompletion(PLAYER_ID)
                        .orElseThrow()
        );

        assertFalse(registry.isReserved(PLAYER_ID));
        assertTrue(
                registry
                        .removeCapacityReservation(PLAYER_ID)
                        .isEmpty()
        );
    }

    @Test
    void disconnectCleanupCanReleaseCapacityAfterPendingState() {
        PendingPlayerFailoverRegistry registry =
                new PendingPlayerFailoverRegistry();

        BackendCapacityReservation reservation =
                reservation(PLAYER_ID);

        assertTrue(
                registry.reserve(
                        PLAYER_ID,
                        reservation
                )
        );

        assertTrue(
                registry
                        .clearForDisconnect(PLAYER_ID)
                        .isEmpty()
        );

        assertSame(
                reservation,
                registry
                        .removeCapacityReservation(PLAYER_ID)
                        .orElseThrow()
        );

        assertFalse(registry.isReserved(PLAYER_ID));
    }

    @Test
    void rejectsCapacityReservationForAnotherPlayer() {
        PendingPlayerFailoverRegistry registry =
                new PendingPlayerFailoverRegistry();

        UUID anotherPlayerId =
                UUID.fromString(
                        "eaf692d8-1708-4138-a881-c096207668bf"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.reserve(
                        PLAYER_ID,
                        reservation(anotherPlayerId)
                )
        );
    }

    private BackendCapacityReservation reservation(
            UUID playerId
    ) {
        return new BackendCapacityReservation(
                REQUEST_ID,
                playerId,
                "skyblock-2"
        );
    }
}

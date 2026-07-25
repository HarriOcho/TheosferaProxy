package com.theosfera.proxy.transfer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendCapacityReservationRegistryTest {

    private final BackendCapacityReservationRegistry registry =
            new BackendCapacityReservationRegistry();

    @Test
    void reservesOnlyAvailableSlot() {
        BackendCapacityReservation first =
                reservation("lobby-1");
        BackendCapacityReservation second =
                reservation("lobby-1");

        assertEquals(
                BackendCapacityReservationResult.RESERVED,
                registry.reserve(first, 99, 100)
        );
        assertEquals(
                BackendCapacityReservationResult.NO_CAPACITY,
                registry.reserve(second, 99, 100)
        );
        assertEquals(1, registry.reservedCount("lobby-1"));
    }

    @Test
    void tracksBackendsIndependently() {
        assertEquals(
                BackendCapacityReservationResult.RESERVED,
                registry.reserve(reservation("lobby-1"), 99, 100)
        );
        assertEquals(
                BackendCapacityReservationResult.RESERVED,
                registry.reserve(reservation("lobby-2"), 99, 100)
        );

        assertEquals(1, registry.reservedCount("lobby-1"));
        assertEquals(1, registry.reservedCount("lobby-2"));
    }

    @Test
    void recognizesExactRepeatedReservation() {
        BackendCapacityReservation reservation =
                reservation("lobby-1");

        registry.reserve(reservation, 0, 100);

        assertEquals(
                BackendCapacityReservationResult.ALREADY_RESERVED,
                registry.reserve(reservation, 0, 100)
        );
        assertEquals(1, registry.reservedCount("lobby-1"));
    }

    @Test
    void rejectsRequestIdentifierConflict() {
        UUID requestId = UUID.randomUUID();

        BackendCapacityReservation first =
                new BackendCapacityReservation(
                        requestId,
                        UUID.randomUUID(),
                        "lobby-1"
                );
        BackendCapacityReservation conflict =
                new BackendCapacityReservation(
                        requestId,
                        UUID.randomUUID(),
                        "lobby-2"
                );

        registry.reserve(first, 0, 100);

        assertEquals(
                BackendCapacityReservationResult.REQUEST_ID_CONFLICT,
                registry.reserve(conflict, 0, 100)
        );
        assertEquals(1, registry.reservedCount("lobby-1"));
        assertEquals(0, registry.reservedCount("lobby-2"));
    }

    @Test
    void releasesOnlyMatchingReservation() {
        BackendCapacityReservation reservation =
                reservation("lobby-1");

        registry.reserve(reservation, 0, 100);

        BackendCapacityReservation different =
                new BackendCapacityReservation(
                        reservation.requestId(),
                        reservation.playerId(),
                        "lobby-2"
                );

        assertTrue(registry.removeIfMatches(different).isEmpty());
        assertEquals(1, registry.reservedCount("lobby-1"));

        assertEquals(
                reservation,
                registry.removeIfMatches(reservation).orElseThrow()
        );
        assertEquals(0, registry.reservedCount("lobby-1"));
    }

    @Test
    void releasesByRequestIdentifier() {
        BackendCapacityReservation reservation =
                reservation("lobby-1");

        registry.reserve(reservation, 0, 100);

        assertEquals(
                reservation,
                registry.removeByRequest(
                        reservation.requestId()
                ).orElseThrow()
        );
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void snapshotIsImmutableAndClearResetsCounts() {
        registry.reserve(reservation("lobby-1"), 0, 100);

        assertThrows(
                UnsupportedOperationException.class,
                () -> registry.snapshot().clear()
        );

        registry.clear();

        assertTrue(registry.snapshot().isEmpty());
        assertEquals(0, registry.reservedCount("lobby-1"));
    }

    @Test
    void validatesCapacityInputs() {
        BackendCapacityReservation reservation =
                reservation("lobby-1");

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.reserve(reservation, -1, 100)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.reserve(reservation, 0, 0)
        );
        assertThrows(
                NullPointerException.class,
                () -> registry.reserve(null, 0, 100)
        );
    }

    @Test
    void validatesReservationIdentity() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendCapacityReservation(
                        null,
                        UUID.randomUUID(),
                        "lobby-1"
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new BackendCapacityReservation(
                        UUID.randomUUID(),
                        null,
                        "lobby-1"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendCapacityReservation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "   "
                )
        );
    }

    private BackendCapacityReservation reservation(
            String backendName
    ) {
        return new BackendCapacityReservation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                backendName
        );
    }
}

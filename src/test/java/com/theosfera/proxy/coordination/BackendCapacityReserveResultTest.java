package com.theosfera.proxy.coordination;

import com.theosfera.proxy.transfer.BackendCapacityReservation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendCapacityReserveResultTest {

    private static BackendCapacityReservation reservation() {
        return new BackendCapacityReservation(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "lobby-1"
        );
    }

    @Test
    void successfulStatusesRequireReservation() {
        BackendCapacityReservation reservation = reservation();

        BackendCapacityReserveResult reserved =
                BackendCapacityReserveResult.withReservation(
                        BackendCapacityReserveResult.Status.RESERVED,
                        reservation
                );

        BackendCapacityReserveResult alreadyReserved =
                BackendCapacityReserveResult.withReservation(
                        BackendCapacityReserveResult.Status.ALREADY_RESERVED,
                        reservation
                );

        assertEquals(reservation, reserved.reservedCapacity().orElseThrow());
        assertEquals(reservation, alreadyReserved.reservedCapacity().orElseThrow());
    }

    @Test
    void unsuccessfulStatusesCannotContainReservation() {
        BackendCapacityReservation reservation = reservation();

        for (BackendCapacityReserveResult.Status status : new BackendCapacityReserveResult.Status[]{
                BackendCapacityReserveResult.Status.REQUEST_ID_CONFLICT,
                BackendCapacityReserveResult.Status.NO_CAPACITY,
                BackendCapacityReserveResult.Status.SESSION_NOT_FOUND,
                BackendCapacityReserveResult.Status.NOT_SESSION_OWNER,
                BackendCapacityReserveResult.Status.OCCUPANCY_UNAVAILABLE,
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BackendCapacityReserveResult.withReservation(
                            status,
                            reservation
                    )
            );
        }
    }

    @Test
    void successfulStatusesCannotOmitReservation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendCapacityReserveResult.withoutReservation(
                        BackendCapacityReserveResult.Status.RESERVED
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendCapacityReserveResult.withoutReservation(
                        BackendCapacityReserveResult.Status.ALREADY_RESERVED
                )
        );
    }

    @Test
    void unsuccessfulStatusesExposeEmptyReservation() {
        BackendCapacityReserveResult unavailable =
                BackendCapacityReserveResult.withoutReservation(
                        BackendCapacityReserveResult.Status.NOT_SESSION_OWNER
                );

        assertTrue(unavailable.reservedCapacity().isEmpty());
        assertFalse(unavailable.reservedCapacity().isPresent());
    }
}

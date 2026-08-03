package com.theosfera.proxy.coordination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendOccupancyReadResultTest {

    @Test
    void availableRequiresNonNegativeOccupancy() {
        BackendOccupancyReadResult result =
                BackendOccupancyReadResult.available(7);

        assertEquals(
                BackendOccupancyReadResult.Status.AVAILABLE,
                result.status()
        );
        assertEquals(7, result.occupancy().orElseThrow());
    }

    @Test
    void zeroOccupancyIsAvailable() {
        BackendOccupancyReadResult result =
                BackendOccupancyReadResult.available(0);

        assertEquals(0, result.occupancy().orElseThrow());
    }

    @Test
    void negativeOccupancyIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendOccupancyReadResult.available(-1)
        );
    }

    @Test
    void unavailableStatusesCannotContainOccupancy() {
        for (BackendOccupancyReadResult.Status status
                : BackendOccupancyReadResult.Status.values()) {
            if (status == BackendOccupancyReadResult.Status.AVAILABLE) {
                continue;
            }

            BackendOccupancyReadResult result =
                    BackendOccupancyReadResult.unavailable(status);

            assertEquals(status, result.status());
            assertTrue(result.occupancy().isEmpty());
        }
    }

    @Test
    void unavailableFactoryRejectsAvailableStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendOccupancyReadResult.unavailable(
                        BackendOccupancyReadResult.Status.AVAILABLE
                )
        );
    }
}

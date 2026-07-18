package com.theosfera.proxy.failover;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingPlayerFailoverRegistryTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "5b8578de-76a6-467f-9211-399b3dfe723a"
            );

    @Test
    void duplicateReservationsCannotBothSucceed() {
        PendingPlayerFailoverRegistry registry =
                new PendingPlayerFailoverRegistry();

        assertTrue(registry.reserve(PLAYER_ID));
        assertFalse(registry.reserve(PLAYER_ID));
    }

    @Test
    void concurrentReservationsCannotBothSucceed()
            throws Exception {
        PendingPlayerFailoverRegistry registry =
                new PendingPlayerFailoverRegistry();
        CountDownLatch start =
                new CountDownLatch(1);
        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Callable<Boolean> reservation =
                () -> {
                    start.await();
                    return registry.reserve(PLAYER_ID);
                };

        try {
            List<Future<Boolean>> results =
                    List.of(
                            executor.submit(reservation),
                            executor.submit(reservation)
                    );

            start.countDown();

            long successfulReservations =
                    results
                            .stream()
                            .filter(result -> {
                                try {
                                    return result.get();
                                } catch (Exception exception) {
                                    throw new IllegalStateException(
                                            exception
                                    );
                                }
                            })
                            .count();

            assertTrue(successfulReservations == 1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clearIsIdempotentWhenReservationIsMissing() {
        PendingPlayerFailoverRegistry registry =
                new PendingPlayerFailoverRegistry();

        assertFalse(registry.clear(PLAYER_ID));
        assertFalse(registry.clear(PLAYER_ID));
    }

    @Test
    void rejectsNullInput() {
        PendingPlayerFailoverRegistry registry =
                new PendingPlayerFailoverRegistry();

        assertThrows(
                NullPointerException.class,
                () -> registry.reserve(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.clear(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.isReserved(null)
        );
    }
}

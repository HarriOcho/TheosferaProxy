package com.theosfera.proxy.failover;

import com.theosfera.proxy.transfer.BackendBootstrapReservation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void successfulConnectionKeepsBootstrapForDisconnectCleanup() {
        PendingPlayerFailoverRegistry registry =
                new PendingPlayerFailoverRegistry();
        BackendBootstrapReservation reservation =
                reservation(PLAYER_ID);

        assertTrue(
                registry.reserve(
                        PLAYER_ID,
                        reservation
                )
        );

        assertTrue(registry.clear(PLAYER_ID));
        assertFalse(registry.isReserved(PLAYER_ID));
        assertSame(
                reservation,
                registry
                        .clearForDisconnect(PLAYER_ID)
                        .orElseThrow()
        );
    }

    @Test
    void rejectsBootstrapReservationForAnotherPlayer() {
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

        assertThrows(
                NullPointerException.class,
                () -> registry.clearForDisconnect(null)
        );
    }

    private BackendBootstrapReservation reservation(
            UUID playerId
    ) {
        return new BackendBootstrapReservation(
                "lobby-1",
                UUID.fromString(
                        "de4ac295-0a64-4eb3-b7c4-f7439e413032"
                ),
                playerId,
                1_750_000_000_000L
        );
    }
}

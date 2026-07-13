package com.theosfera.proxy.transfer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendBootstrapRegistryTest {

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private static final long NOW =
            1_750_000_000_000L;

    private final BackendBootstrapRegistry registry =
            new BackendBootstrapRegistry(
                    Duration.ofSeconds(30)
            );

    @Test
    void reservesTargetInBothIndexes() {
        BackendBootstrapReservation reservation =
                reservation(
                        "skyblock-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                );

        assertEquals(
                BackendBootstrapRegistrationResult.RESERVED,
                registry.register(reservation)
        );

        assertSame(
                reservation,
                registry.findByTarget(
                        "skyblock-1"
                ).orElseThrow()
        );

        assertSame(
                reservation,
                registry.findByRequest(
                        REQUEST_ID
                ).orElseThrow()
        );

        assertEquals(1, registry.size());
    }

    @Test
    void acceptsIdenticalReservationIdempotently() {
        BackendBootstrapReservation reservation =
                reservation(
                        "skyblock-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                );

        registry.register(reservation);

        assertEquals(
                BackendBootstrapRegistrationResult
                        .ALREADY_RESERVED,
                registry.register(reservation)
        );

        assertEquals(1, registry.size());
    }

    @Test
    void rejectsDifferentRequestForBusyTarget() {
        registry.register(
                reservation(
                        "skyblock-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                )
        );

        assertEquals(
                BackendBootstrapRegistrationResult.TARGET_BUSY,
                registry.register(
                        reservation(
                                "skyblock-1",
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                NOW + 1_000L
                        )
                )
        );

        assertEquals(1, registry.size());
    }

    @Test
    void rejectsRequestIdUsedByAnotherTarget() {
        registry.register(
                reservation(
                        "skyblock-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                )
        );

        assertEquals(
                BackendBootstrapRegistrationResult
                        .REQUEST_ID_CONFLICT,
                registry.register(
                        reservation(
                                "lobby-1",
                                REQUEST_ID,
                                UUID.randomUUID(),
                                NOW + 1_000L
                        )
                )
        );

        assertEquals(1, registry.size());
    }

    @Test
    void atomicallyReplacesExpiredReservation() {
        BackendBootstrapReservation expired =
                reservation(
                        "skyblock-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                );

        BackendBootstrapReservation replacement =
                reservation(
                        "skyblock-1",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        NOW + 30_000L
                );

        registry.register(expired);

        assertEquals(
                BackendBootstrapRegistrationResult.RESERVED,
                registry.register(replacement)
        );

        assertSame(
                replacement,
                registry.findByTarget(
                        "skyblock-1"
                ).orElseThrow()
        );

        assertTrue(
                registry.findByRequest(
                        REQUEST_ID
                ).isEmpty()
        );

        assertEquals(1, registry.size());
    }

    @Test
    void doesNotReplaceReservationBeforeExpiration() {
        BackendBootstrapReservation first =
                reservation(
                        "skyblock-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                );

        registry.register(first);

        assertEquals(
                BackendBootstrapRegistrationResult.TARGET_BUSY,
                registry.register(
                        reservation(
                                "skyblock-1",
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                NOW + 29_999L
                        )
                )
        );

        assertSame(
                first,
                registry.findByTarget(
                        "skyblock-1"
                ).orElseThrow()
        );
    }

    @Test
    void removesReservationFromBothIndexes() {
        BackendBootstrapReservation reservation =
                reservation(
                        "skyblock-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                );

        registry.register(reservation);

        assertSame(
                reservation,
                registry.removeByTarget(
                        "skyblock-1"
                ).orElseThrow()
        );

        assertTrue(
                registry.findByTarget(
                        "skyblock-1"
                ).isEmpty()
        );

        assertTrue(
                registry.findByRequest(
                        REQUEST_ID
                ).isEmpty()
        );
    }

    @Test
    void rejectsInvalidExpirationAndNullInputs() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendBootstrapRegistry(null)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendBootstrapRegistry(
                        Duration.ZERO
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.register(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.findByTarget(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.findByRequest(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.removeByTarget(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.removeByRequest(null)
        );
    }

    private BackendBootstrapReservation reservation(
            String targetBackendName,
            UUID requestId,
            UUID playerId,
            long createdAt
    ) {
        return new BackendBootstrapReservation(
                targetBackendName,
                requestId,
                playerId,
                createdAt
        );
    }
}

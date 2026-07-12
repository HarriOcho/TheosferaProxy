package com.theosfera.proxy.transfer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingPlayerTransferRegistryTest {

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final UUID OTHER_REQUEST_ID =
            UUID.fromString(
                    "66666666-7777-8888-9999-aaaaaaaaaaaa"
            );

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private static final UUID OTHER_PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private final PendingPlayerTransferRegistry registry =
            new PendingPlayerTransferRegistry();

    @Test
    void registersAndFindsTransferByBothIndexes() {
        PendingPlayerTransfer transfer = createTransfer(
                REQUEST_ID,
                PLAYER_ID,
                "lobby-1",
                "skyblock-1"
        );

        assertEquals(
                PlayerTransferRegistrationResult.REGISTERED,
                registry.register(transfer)
        );

        assertEquals(
                transfer,
                registry
                        .findByPlayer(PLAYER_ID)
                        .orElseThrow()
        );

        assertEquals(
                transfer,
                registry
                        .findByRequest(REQUEST_ID)
                        .orElseThrow()
        );
    }

    @Test
    void treatsIdenticalTransferAsIdempotent() {
        PendingPlayerTransfer transfer = createTransfer(
                REQUEST_ID,
                PLAYER_ID,
                "lobby-1",
                "skyblock-1"
        );

        registry.register(transfer);

        assertEquals(
                PlayerTransferRegistrationResult
                        .ALREADY_REGISTERED,
                registry.register(transfer)
        );

        assertEquals(1, registry.snapshotByPlayer().size());
    }

    @Test
    void rejectsDifferentTransferForBusyPlayer() {
        registry.register(
                createTransfer(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1",
                        "skyblock-1"
                )
        );

        assertEquals(
                PlayerTransferRegistrationResult.PLAYER_BUSY,
                registry.register(
                        createTransfer(
                                OTHER_REQUEST_ID,
                                PLAYER_ID,
                                "skyblock-1",
                                "lobby-1"
                        )
                )
        );
    }

    @Test
    void rejectsRequestIdentifierConflict() {
        registry.register(
                createTransfer(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1",
                        "skyblock-1"
                )
        );

        assertEquals(
                PlayerTransferRegistrationResult
                        .REQUEST_ID_CONFLICT,
                registry.register(
                        createTransfer(
                                REQUEST_ID,
                                OTHER_PLAYER_ID,
                                "lobby-1",
                                "skyblock-1"
                        )
                )
        );
    }

    @Test
    void removesTransferByRequestFromBothIndexes() {
        PendingPlayerTransfer transfer = createTransfer(
                REQUEST_ID,
                PLAYER_ID,
                "lobby-1",
                "skyblock-1"
        );

        registry.register(transfer);

        assertEquals(
                transfer,
                registry.remove(REQUEST_ID).orElseThrow()
        );

        assertTrue(
                registry.findByRequest(REQUEST_ID).isEmpty()
        );

        assertTrue(
                registry.findByPlayer(PLAYER_ID).isEmpty()
        );
    }

    @Test
    void removesTransferByPlayerFromBothIndexes() {
        PendingPlayerTransfer transfer = createTransfer(
                REQUEST_ID,
                PLAYER_ID,
                "lobby-1",
                "skyblock-1"
        );

        registry.register(transfer);

        assertEquals(
                transfer,
                registry
                        .removeByPlayer(PLAYER_ID)
                        .orElseThrow()
        );

        assertTrue(
                registry.findByPlayer(PLAYER_ID).isEmpty()
        );

        assertTrue(
                registry.findByRequest(REQUEST_ID).isEmpty()
        );
    }

    @Test
    void clearsBothIndexes() {
        registry.register(
                createTransfer(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1",
                        "skyblock-1"
                )
        );

        registry.clear();

        assertTrue(registry.snapshotByPlayer().isEmpty());
        assertTrue(
                registry.findByRequest(REQUEST_ID).isEmpty()
        );
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(
                NullPointerException.class,
                () -> registry.register(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.findByPlayer(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.findByRequest(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.remove(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.removeByPlayer(null)
        );
    }

    private PendingPlayerTransfer createTransfer(
            UUID requestId,
            UUID playerId,
            String sourceBackendName,
            String targetBackendName
    ) {
        return new PendingPlayerTransfer(
                requestId,
                playerId,
                sourceBackendName,
                targetBackendName,
                1_750_000_000_000L
        );
    }
}

package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DistributedPlayerTransferInitialExclusionsTest {

    private static final UUID REQUEST_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );
    private static final UUID PLAYER_ID = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );
    private static final long REQUESTED_AT = 1_750_000_000_000L;

    @Test
    void passesInitialExclusionsToFirstAllocation() {
        BackendBootstrapRegistry bootstrapRegistry =
                new BackendBootstrapRegistry();
        PendingPlayerTransferRegistry transferRegistry =
                new PendingPlayerTransferRegistry();
        DistributedPlayerTransferTargetAllocationService allocationService =
                mock(DistributedPlayerTransferTargetAllocationService.class);
        PlayerTransferExecutor transferExecutor =
                mock(PlayerTransferExecutor.class);
        DistributedBackendCapacityReleaseService releaseService =
                mock(DistributedBackendCapacityReleaseService.class);
        BackendCapacityHandoffService handoffService =
                mock(BackendCapacityHandoffService.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        DistributedPlayerTransferRetryCoordinator coordinator =
                new DistributedPlayerTransferRetryCoordinator(
                        bootstrapRegistry,
                        transferRegistry,
                        allocationService,
                        transferExecutor,
                        releaseService,
                        handoffService,
                        mock(Logger.class)
                );

        Set<String> initialExclusions = Set.of("lobby-1");
        when(allocationService.allocate(
                player,
                REQUEST_ID,
                "lobby-1",
                BackendType.LOBBY,
                REQUESTED_AT,
                initialExclusions
        )).thenReturn(
                CompletableFuture.completedFuture(
                        DistributedPlayerTransferTargetAllocation.unavailable(
                                TransferTargetResolution.notAuthenticated()
                        )
                )
        );

        coordinator.start(
                request(player),
                initialExclusions
        );

        verify(allocationService).allocate(
                player,
                REQUEST_ID,
                "lobby-1",
                BackendType.LOBBY,
                REQUESTED_AT,
                initialExclusions
        );
        verifyNoInteractions(
                transferExecutor,
                releaseService,
                handoffService
        );
    }

    private DistributedPlayerTransferRetryCoordinator.TransferRetryRequest
    request(Player player) {
        return new DistributedPlayerTransferRetryCoordinator.TransferRetryRequest(
                REQUEST_ID,
                PLAYER_ID,
                "lobby-1",
                BackendType.LOBBY,
                REQUESTED_AT,
                player,
                () -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                }
        );
    }
}

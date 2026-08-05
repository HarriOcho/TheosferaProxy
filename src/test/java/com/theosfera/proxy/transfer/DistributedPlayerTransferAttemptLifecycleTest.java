package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedPlayerTransferAttemptLifecycleTest {

    private static final UUID REQUEST_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );
    private static final UUID PLAYER_ID = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );

    private PendingPlayerTransferRegistry transferRegistry;
    private BackendBootstrapRegistry bootstrapRegistry;
    private DistributedBackendCapacityReleaseService releaseService;
    private BackendCapacityHandoffService handoffService;
    private DistributedPlayerTransferAttemptLifecycle lifecycle;
    private PendingPlayerTransfer transfer;
    private BackendCapacityReserveRequest capacityRequest;

    @BeforeEach
    void setUp() {
        transferRegistry = new PendingPlayerTransferRegistry();
        bootstrapRegistry = new BackendBootstrapRegistry();
        releaseService = mock(DistributedBackendCapacityReleaseService.class);
        handoffService = mock(BackendCapacityHandoffService.class);
        lifecycle = new DistributedPlayerTransferAttemptLifecycle(
                transferRegistry,
                bootstrapRegistry,
                releaseService,
                handoffService,
                mock(Logger.class)
        );
        transfer = new PendingPlayerTransfer(
                REQUEST_ID,
                PLAYER_ID,
                "lobby-1",
                "skyblock-1",
                2_000L
        );
        capacityRequest = request();
    }

    @Test
    void failedAttemptRemovesMatchingStateAndRequiresConfirmedRelease() {
        transferRegistry.register(transfer);
        BackendBootstrapReservation bootstrap =
                new BackendBootstrapReservation(
                        "skyblock-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        2_000L
                );
        bootstrapRegistry.register(bootstrap);
        when(releaseService.releaseIfOwned(capacityRequest))
                .thenReturn(CompletableFuture.completedFuture(true));

        DistributedPlayerTransferAttemptLifecycle.CleanupResult result =
                lifecycle.cleanupFailedAttempt(
                        transfer,
                        capacityRequest,
                        bootstrap
                ).toCompletableFuture().join();

        assertTrue(result.transferMatched());
        assertTrue(result.capacityReleased());
        assertTrue(transferRegistry.findByRequest(REQUEST_ID).isEmpty());
        assertTrue(bootstrapRegistry.findByRequest(REQUEST_ID).isEmpty());
    }

    @Test
    void failedAttemptStillReleasesExactCapacityWhenPendingIsAlreadyGone() {
        when(releaseService.releaseIfOwned(capacityRequest))
                .thenReturn(CompletableFuture.completedFuture(true));

        DistributedPlayerTransferAttemptLifecycle.CleanupResult result =
                lifecycle.cleanupFailedAttempt(
                        transfer,
                        capacityRequest,
                        null
                ).toCompletableFuture().join();

        assertFalse(result.transferMatched());
        assertTrue(result.capacityReleased());
        verify(releaseService).releaseIfOwned(capacityRequest);
    }

    @Test
    void successfulConnectionMovesMatchingTransferIntoHandoffWithoutRelease() {
        transferRegistry.register(transfer);
        when(handoffService.registerAfterConnectionSuccess(capacityRequest))
                .thenReturn(BackendCapacityHandoffRegistrationResult.REGISTERED);

        DistributedPlayerTransferAttemptLifecycle
                .SuccessfulConnectionDisposition disposition =
                lifecycle.completeSuccessfulConnection(
                        transfer,
                        capacityRequest
                );

        assertEquals(
                DistributedPlayerTransferAttemptLifecycle
                        .SuccessfulConnectionDisposition.HANDOFF_REGISTERED,
                disposition
        );
        assertTrue(transferRegistry.findByRequest(REQUEST_ID).isEmpty());
        verify(handoffService).registerAfterConnectionSuccess(capacityRequest);
        verify(releaseService, never()).releaseIfOwned(any());
    }

    @Test
    void lateSuccessfulConnectionDoesNotRegisterOrReleaseAnything() {
        DistributedPlayerTransferAttemptLifecycle
                .SuccessfulConnectionDisposition disposition =
                lifecycle.completeSuccessfulConnection(
                        transfer,
                        capacityRequest
                );

        assertEquals(
                DistributedPlayerTransferAttemptLifecycle
                        .SuccessfulConnectionDisposition.LATE_RESULT,
                disposition
        );
        verify(handoffService, never())
                .registerAfterConnectionSuccess(any());
        verify(releaseService, never()).releaseIfOwned(any());
    }

    @Test
    void handoffConflictFallsBackToTtlWithoutRelease() {
        transferRegistry.register(transfer);
        when(handoffService.registerAfterConnectionSuccess(capacityRequest))
                .thenReturn(BackendCapacityHandoffRegistrationResult.PLAYER_BUSY);

        DistributedPlayerTransferAttemptLifecycle
                .SuccessfulConnectionDisposition disposition =
                lifecycle.completeSuccessfulConnection(
                        transfer,
                        capacityRequest
                );

        assertEquals(
                DistributedPlayerTransferAttemptLifecycle
                        .SuccessfulConnectionDisposition.HANDOFF_TTL_FALLBACK,
                disposition
        );
        verify(releaseService, never()).releaseIfOwned(any());
    }

    private BackendCapacityReserveRequest request() {
        return new BackendCapacityReserveRequest(
                new BackendCapacityReservation(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1"
                ),
                new PlayerSessionLease(
                        new AuthenticatedPlayerSession(
                                PLAYER_ID,
                                "HarriOcho",
                                1_000L
                        ),
                        new ProxyInstanceIdentity(
                                "proxy-1",
                                UUID.fromString(
                                        "99999999-8888-7777-6666-555555555555"
                                )
                        ),
                        7L
                )
        );
    }
}

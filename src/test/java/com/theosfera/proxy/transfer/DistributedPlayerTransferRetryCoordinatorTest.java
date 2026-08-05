package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedPlayerTransferRetryCoordinatorTest {

    private static final UUID REQUEST_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );
    private static final UUID PLAYER_ID = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );
    private static final long REQUESTED_AT = 1_750_000_000_000L;

    private BackendBootstrapRegistry bootstrapRegistry;
    private PendingPlayerTransferRegistry transferRegistry;
    private DistributedPlayerTransferTargetAllocationService allocationService;
    private PlayerTransferExecutor transferExecutor;
    private DistributedBackendCapacityReleaseService releaseService;
    private BackendCapacityHandoffService handoffService;
    private Player player;
    private DistributedPlayerTransferRetryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        bootstrapRegistry = new BackendBootstrapRegistry();
        transferRegistry = new PendingPlayerTransferRegistry();
        allocationService = mock(
                DistributedPlayerTransferTargetAllocationService.class
        );
        transferExecutor = mock(PlayerTransferExecutor.class);
        releaseService = mock(DistributedBackendCapacityReleaseService.class);
        handoffService = mock(BackendCapacityHandoffService.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        coordinator = new DistributedPlayerTransferRetryCoordinator(
                bootstrapRegistry,
                transferRegistry,
                allocationService,
                transferExecutor,
                releaseService,
                handoffService,
                mock(Logger.class)
        );
    }

    @Test
    void successfulConnectionTransfersReservationToHandoffWithoutRelease() {
        Attempt first = attempt("skyblock-1", false);
        transferRegistry.register(first.transfer());
        when(allocation(Set.of())).thenReturn(
                CompletableFuture.completedFuture(first.allocation())
        );
        when(transferExecutor.execute(player, first.server()))
                .thenReturn(CompletableFuture.completedFuture(
                        PlayerTransferCompletion.success()
                ));
        when(handoffService.registerAfterConnectionSuccess(
                first.capacityRequest()
        )).thenReturn(BackendCapacityHandoffRegistrationResult.REGISTERED);

        CallbackState callbacks = new CallbackState();
        coordinator.start(callbacks.request());

        assertEquals(
                List.of(TransferResultStatus.SUCCESS),
                callbacks.completionStatuses()
        );
        assertTrue(transferRegistry.findByPlayer(PLAYER_ID).isEmpty());
        verify(handoffService).registerAfterConnectionSuccess(
                first.capacityRequest()
        );
        verify(releaseService, never()).releaseIfOwned(any());
    }

    @Test
    void failedAttemptWaitsForConfirmedReleaseBeforeRetry() {
        Attempt first = attempt("skyblock-1", false);
        Attempt second = attempt("skyblock-2", false);
        transferRegistry.register(first.transfer());

        when(allocation(Set.of())).thenReturn(
                CompletableFuture.completedFuture(first.allocation())
        );
        when(allocation(Set.of("skyblock-1"))).thenAnswer(invocation -> {
            transferRegistry.register(second.transfer());
            return CompletableFuture.completedFuture(second.allocation());
        });
        when(transferExecutor.execute(player, first.server()))
                .thenReturn(CompletableFuture.completedFuture(
                        PlayerTransferCompletion.failed()
                ));
        when(transferExecutor.execute(player, second.server()))
                .thenReturn(CompletableFuture.completedFuture(
                        PlayerTransferCompletion.success()
                ));
        when(handoffService.registerAfterConnectionSuccess(
                second.capacityRequest()
        )).thenReturn(BackendCapacityHandoffRegistrationResult.REGISTERED);

        CompletableFuture<Boolean> release = new CompletableFuture<>();
        when(releaseService.releaseIfOwned(first.capacityRequest()))
                .thenReturn(release);

        CallbackState callbacks = new CallbackState();
        coordinator.start(callbacks.request());

        verify(allocationService, times(1)).allocate(
                eq(player),
                eq(REQUEST_ID),
                eq("lobby-1"),
                eq(BackendType.SKYBLOCK),
                eq(REQUESTED_AT),
                any()
        );
        assertTrue(callbacks.completionStatuses().isEmpty());

        release.complete(true);

        verify(transferExecutor).execute(player, second.server());
        assertEquals(
                List.of(TransferResultStatus.SUCCESS),
                callbacks.completionStatuses()
        );
    }

    @Test
    void unconfirmedReleaseFailsClosedAndDoesNotRetry() {
        Attempt first = attempt("skyblock-1", false);
        transferRegistry.register(first.transfer());
        when(allocation(Set.of())).thenReturn(
                CompletableFuture.completedFuture(first.allocation())
        );
        when(transferExecutor.execute(player, first.server()))
                .thenReturn(CompletableFuture.completedFuture(
                        PlayerTransferCompletion.failed()
                ));
        when(releaseService.releaseIfOwned(first.capacityRequest()))
                .thenReturn(CompletableFuture.completedFuture(false));

        CallbackState callbacks = new CallbackState();
        coordinator.start(callbacks.request());

        assertEquals(
                List.of(TransferResultStatus.FAILED),
                callbacks.completionStatuses()
        );
        verify(allocationService, never()).allocate(
                eq(player),
                eq(REQUEST_ID),
                eq("lobby-1"),
                eq(BackendType.SKYBLOCK),
                eq(REQUESTED_AT),
                eq(Set.of("skyblock-1"))
        );
    }

    @Test
    void timeoutReleasesExactlyButNeverRetries() {
        Attempt first = attempt("skyblock-1", true);
        transferRegistry.register(first.transfer());
        when(allocation(Set.of())).thenReturn(
                CompletableFuture.completedFuture(first.allocation())
        );
        when(transferExecutor.execute(player, first.server()))
                .thenReturn(CompletableFuture.completedFuture(
                        PlayerTransferCompletion.timedOut()
                ));
        when(releaseService.releaseIfOwned(first.capacityRequest()))
                .thenReturn(CompletableFuture.completedFuture(true));

        CallbackState callbacks = new CallbackState();
        coordinator.start(callbacks.request());

        assertEquals(
                List.of(TransferResultStatus.TIMED_OUT),
                callbacks.completionStatuses()
        );
        assertTrue(
                bootstrapRegistry.findByRequest(REQUEST_ID).isEmpty()
        );
        verify(releaseService).releaseIfOwned(first.capacityRequest());
        verify(allocationService, times(1)).allocate(
                eq(player),
                eq(REQUEST_ID),
                eq("lobby-1"),
                eq(BackendType.SKYBLOCK),
                eq(REQUESTED_AT),
                any()
        );
    }

    @Test
    void busyBootstrapRetriesOnlyAfterCapacityRelease() {
        Attempt first = attempt("skyblock-1", true);
        Attempt second = attempt("skyblock-2", false);
        transferRegistry.register(first.transfer());

        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "skyblock-1",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        REQUESTED_AT - 1
                )
        );
        when(allocation(Set.of())).thenReturn(
                CompletableFuture.completedFuture(first.allocation())
        );
        when(allocation(Set.of("skyblock-1"))).thenAnswer(invocation -> {
            transferRegistry.register(second.transfer());
            return CompletableFuture.completedFuture(second.allocation());
        });
        when(releaseService.releaseIfOwned(first.capacityRequest()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(transferExecutor.execute(player, second.server()))
                .thenReturn(CompletableFuture.completedFuture(
                        PlayerTransferCompletion.success()
                ));
        when(handoffService.registerAfterConnectionSuccess(
                second.capacityRequest()
        )).thenReturn(BackendCapacityHandoffRegistrationResult.REGISTERED);

        CallbackState callbacks = new CallbackState();
        coordinator.start(callbacks.request());

        verify(releaseService).releaseIfOwned(first.capacityRequest());
        verify(transferExecutor).execute(player, second.server());
        assertEquals(
                List.of(TransferResultStatus.SUCCESS),
                callbacks.completionStatuses()
        );
    }

    @Test
    void capacityRejectionIsExposedWithoutStartingConnection() {
        when(allocation(Set.of())).thenReturn(
                CompletableFuture.completedFuture(
                        DistributedPlayerTransferTargetAllocation
                                .capacityRejected(
                                        TransferTargetResolution.noCapacity(),
                                        BackendCapacityReserveResult.Status
                                                .COORDINATION_UNAVAILABLE
                                )
                )
        );

        CallbackState callbacks = new CallbackState();
        coordinator.start(callbacks.request());

        assertEquals(
                List.of(
                        BackendCapacityReserveResult.Status
                                .COORDINATION_UNAVAILABLE
                ),
                callbacks.capacityRejections()
        );
        verify(transferExecutor, never()).execute(any(), any());
        verify(releaseService, never()).releaseIfOwned(any());
    }

    @Test
    void lateSuccessfulCompletionDoesNotReleaseReservation() {
        Attempt first = attempt("skyblock-1", false);
        transferRegistry.register(first.transfer());
        when(allocation(Set.of())).thenReturn(
                CompletableFuture.completedFuture(first.allocation())
        );
        CompletableFuture<PlayerTransferCompletion> connection =
                new CompletableFuture<>();
        when(transferExecutor.execute(player, first.server()))
                .thenReturn(connection);

        CallbackState callbacks = new CallbackState();
        coordinator.start(callbacks.request());
        transferRegistry.removeIfMatches(first.transfer());
        connection.complete(PlayerTransferCompletion.success());

        assertEquals(1, callbacks.lateResults().size());
        assertTrue(callbacks.completionStatuses().isEmpty());
        verify(handoffService, never())
                .registerAfterConnectionSuccess(any());
        verify(releaseService, never()).releaseIfOwned(any());
    }

    @Test
    void handoffConflictAfterSuccessfulConnectionStillReportsSuccess() {
        Attempt first = attempt("skyblock-1", false);
        transferRegistry.register(first.transfer());
        when(allocation(Set.of())).thenReturn(
                CompletableFuture.completedFuture(first.allocation())
        );
        when(transferExecutor.execute(player, first.server()))
                .thenReturn(CompletableFuture.completedFuture(
                        PlayerTransferCompletion.success()
                ));
        when(handoffService.registerAfterConnectionSuccess(
                first.capacityRequest()
        )).thenReturn(
                BackendCapacityHandoffRegistrationResult.PLAYER_BUSY
        );

        CallbackState callbacks = new CallbackState();
        coordinator.start(callbacks.request());

        assertEquals(
                List.of(TransferResultStatus.SUCCESS),
                callbacks.completionStatuses()
        );
        verify(releaseService, never()).releaseIfOwned(any());
    }

    private java.util.concurrent.CompletionStage<
            DistributedPlayerTransferTargetAllocation> allocation(
            Set<String> exclusions
    ) {
        return allocationService.allocate(
                player,
                REQUEST_ID,
                "lobby-1",
                BackendType.SKYBLOCK,
                REQUESTED_AT,
                exclusions
        );
    }

    private Attempt attempt(String backendName, boolean bootstrap) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(backendName);

        PendingPlayerTransfer transfer = new PendingPlayerTransfer(
                REQUEST_ID,
                PLAYER_ID,
                "lobby-1",
                backendName,
                REQUESTED_AT
        );
        BackendCapacityReserveRequest capacityRequest =
                new BackendCapacityReserveRequest(
                        new BackendCapacityReservation(
                                REQUEST_ID,
                                PLAYER_ID,
                                backendName
                        ),
                        lease()
                );
        TransferTargetResolution resolution = bootstrap
                ? TransferTargetResolution.bootstrapRequired(server)
                : TransferTargetResolution.resolved(server);
        DistributedPlayerTransferTargetAllocation allocation =
                DistributedPlayerTransferTargetAllocation.allocated(
                        resolution,
                        transfer,
                        capacityRequest,
                        BackendCapacityReserveResult.Status.RESERVED
                );
        return new Attempt(
                server,
                transfer,
                capacityRequest,
                allocation
        );
    }

    private PlayerSessionLease lease() {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        REQUESTED_AT - 100
                ),
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.fromString(
                                "99999999-8888-7777-6666-555555555555"
                        )
                ),
                7L
        );
    }

    private final class CallbackState {
        private final List<TransferResultStatus> completionStatuses =
                new ArrayList<>();
        private final List<BackendCapacityReserveResult.Status>
                capacityRejections = new ArrayList<>();
        private final List<PendingPlayerTransfer> lateResults =
                new ArrayList<>();

        private DistributedPlayerTransferRetryCoordinator.TransferRetryRequest
        request() {
            return new DistributedPlayerTransferRetryCoordinator
                    .TransferRetryRequest(
                    REQUEST_ID,
                    PLAYER_ID,
                    "lobby-1",
                    BackendType.SKYBLOCK,
                    REQUESTED_AT,
                    player,
                    () -> {
                    },
                    ignored -> {
                    },
                    ignored -> {
                    },
                    capacityRejections::add,
                    ignored -> {
                    },
                    ignored -> {
                    },
                    completion -> completionStatuses.add(
                            completion.status()
                    ),
                    lateResults::add
            );
        }

        private List<TransferResultStatus> completionStatuses() {
            return List.copyOf(completionStatuses);
        }

        private List<BackendCapacityReserveResult.Status>
        capacityRejections() {
            return List.copyOf(capacityRejections);
        }

        private List<PendingPlayerTransfer> lateResults() {
            return List.copyOf(lateResults);
        }
    }

    private record Attempt(
            RegisteredServer server,
            PendingPlayerTransfer transfer,
            BackendCapacityReserveRequest capacityRequest,
            DistributedPlayerTransferTargetAllocation allocation
    ) {
    }
}

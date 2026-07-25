package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerTransferRetryCoordinatorTest {

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );
    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );
    private static final UUID OTHER_PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );
    private static final long NOW =
            1_750_000_000_000L;

    private PendingPlayerTransferRegistry transferRegistry;
    private BackendBootstrapRegistry bootstrapRegistry;
    private TransferTargetResolver targetResolver;
    private PlayerTransferTargetAllocationService allocationService;
    private PlayerTransferExecutor transferExecutor;
    private Player player;
    private RegisteredServer target;
    private PlayerTransferRetryCoordinator coordinator;
    private List<PlayerTransferCompletion> completions;
    private List<BackendBootstrapRegistrationResult>
            bootstrapRejections;

    @BeforeEach
    void setUp() {
        transferRegistry = new PendingPlayerTransferRegistry();
        bootstrapRegistry = new BackendBootstrapRegistry();
        targetResolver = mock(TransferTargetResolver.class);
        allocationService =
                new PlayerTransferTargetAllocationService(
                        targetResolver,
                        transferRegistry
                );
        transferExecutor = mock(PlayerTransferExecutor.class);
        player = mock(Player.class);
        target = server("lobby-1");
        completions = new ArrayList<>();
        bootstrapRejections = new ArrayList<>();

        when(targetResolver.reserveCapacity(any(), any()))
                .thenReturn(BackendCapacityReservationResult.RESERVED);

        coordinator =
                new PlayerTransferRetryCoordinator(
                        bootstrapRegistry,
                        targetResolver,
                        transferRegistry,
                        allocationService,
                        transferExecutor
                );
    }

    @Test
    void lateCompletionReleasesOnlyOldCapacityReservation() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());

        transferRegistry.remove(REQUEST_ID);

        BackendCapacityReservation newerReservation =
                new BackendCapacityReservation(
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        "lobby-2"
                );

        future.complete(PlayerTransferCompletion.failed());

        verify(targetResolver).releaseCapacity(
                new BackendCapacityReservation(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1"
                )
        );
        verify(targetResolver, never()).releaseCapacity(
                newerReservation
        );
    }

    @Test
    void lateCompletionReleasesOnlyOldBootstrapReservation() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());

        transferRegistry.remove(REQUEST_ID);
        bootstrapRegistry.removeByRequest(REQUEST_ID);

        BackendBootstrapReservation newerReservation =
                new BackendBootstrapReservation(
                        "lobby-2",
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        NOW + 1
                );

        bootstrapRegistry.register(newerReservation);

        future.complete(PlayerTransferCompletion.failed());

        assertEquals(
                newerReservation,
                bootstrapRegistry.findByRequest(REQUEST_ID).orElseThrow()
        );
    }

    @Test
    void lateCompletionPreservesNewerTransferCapacityAndBootstrap() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());

        transferRegistry.remove(REQUEST_ID);
        bootstrapRegistry.removeByRequest(REQUEST_ID);

        PendingPlayerTransfer newerTransfer =
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        "skyblock-2",
                        "lobby-2",
                        NOW + 1
                );
        BackendCapacityReservation newerCapacity =
                new BackendCapacityReservation(
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        "lobby-2"
                );
        BackendBootstrapReservation newerBootstrap =
                new BackendBootstrapReservation(
                        "lobby-2",
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        NOW + 1
                );

        transferRegistry.register(newerTransfer);
        bootstrapRegistry.register(newerBootstrap);

        future.complete(PlayerTransferCompletion.failed());

        assertEquals(
                newerTransfer,
                transferRegistry.findByRequest(REQUEST_ID).orElseThrow()
        );
        assertEquals(
                newerBootstrap,
                bootstrapRegistry.findByRequest(REQUEST_ID).orElseThrow()
        );
        verify(targetResolver, never()).releaseCapacity(newerCapacity);
    }

    @Test
    void lateCompletionDoesNotSendDuplicateTerminalResult() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());

        transferRegistry.remove(REQUEST_ID);

        future.complete(PlayerTransferCompletion.failed());

        assertTrue(completions.isEmpty());
    }

    @Test
    void timedOutAttemptIsTerminal() {
        RegisteredServer secondTarget = server("lobby-2");

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(secondTarget));
        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.timedOut()
                        )
                );

        coordinator.start(request());

        assertEquals(
                List.of(PlayerTransferCompletion.timedOut()),
                completions
        );
        verify(transferExecutor, never()).execute(player, secondTarget);
    }

    @Test
    void timedOutAttemptDoesNotResolveExcludedFallback() {
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.timedOut()
                        )
                );

        coordinator.start(request());

        verify(targetResolver, never()).resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
    }

    @Test
    void timedOutAttemptReleasesExactResources() {
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.timedOut()
                        )
                );

        coordinator.start(request());

        assertTrue(transferRegistry.findByRequest(REQUEST_ID).isEmpty());
        assertTrue(bootstrapRegistry.findByRequest(REQUEST_ID).isEmpty());
        verify(targetResolver).releaseCapacity(
                new BackendCapacityReservation(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1"
                )
        );
    }

    @Test
    void lateTimedOutCompletionDoesNotSendDuplicateResult() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());

        transferRegistry.remove(REQUEST_ID);
        future.complete(PlayerTransferCompletion.timedOut());

        assertTrue(completions.isEmpty());
    }

    @Test
    void repeatedExcludedTargetFailsClosedWithoutLoop() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(target));
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());

        future.complete(PlayerTransferCompletion.failed());

        assertEquals(
                List.of(PlayerTransferCompletion.failed()),
                completions
        );
        verify(transferExecutor).execute(player, target);
    }

    @Test
    void repeatedExcludedTargetCleansPendingTransferAndCapacity() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(target));
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());
        future.complete(PlayerTransferCompletion.failed());

        assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
        verify(targetResolver).releaseCapacity(
                new BackendCapacityReservation(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1"
                )
        );
    }

    @Test
    void repeatedExcludedTargetDoesNotExecuteConnection() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(target));
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());
        future.complete(PlayerTransferCompletion.failed());

        verify(transferExecutor, times(1)).execute(player, target);
    }

    @Test
    void repeatedExcludedTargetWithNoCapacityTerminatesWithoutLoop() {
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.reserveCapacity(any(), any()))
                .thenReturn(BackendCapacityReservationResult.NO_CAPACITY);

        assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(1),
                () -> coordinator.start(request())
        );

        assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
        assertEquals(
                List.of(PlayerTransferCompletion.failed()),
                completions
        );
    }

    @Test
    void repeatedExcludedTargetSendsExactlyOneTerminalResult() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(target));
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());
        future.complete(PlayerTransferCompletion.failed());

        assertEquals(1, completions.size());
    }

    @Test
    void lateSuccessfulCompletionRemovesOldBootstrapReservation() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());

        transferRegistry.remove(REQUEST_ID);
        future.complete(PlayerTransferCompletion.success());

        assertTrue(bootstrapRegistry.findByRequest(REQUEST_ID).isEmpty());
        assertTrue(completions.isEmpty());
    }

    @Test
    void lateSuccessfulCompletionPreservesNewerBootstrapReservation() {
        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        coordinator.start(request());

        transferRegistry.remove(REQUEST_ID);
        bootstrapRegistry.removeByRequest(REQUEST_ID);

        BackendBootstrapReservation newerReservation =
                new BackendBootstrapReservation(
                        "lobby-2",
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        NOW + 1
                );

        bootstrapRegistry.register(newerReservation);
        future.complete(PlayerTransferCompletion.success());

        assertEquals(
                newerReservation,
                bootstrapRegistry.findByRequest(REQUEST_ID).orElseThrow()
        );
    }

    @Test
    void authoritativeSuccessKeepsWinningBootstrapReservation() {
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        coordinator.start(request());

        assertEquals(
                REQUEST_ID,
                bootstrapRegistry.findByRequest(REQUEST_ID)
                        .orElseThrow()
                        .requestId()
        );
        assertEquals(
                List.of(PlayerTransferCompletion.success()),
                completions
        );
    }

    @Test
    void staleBootstrapRejectionDoesNotRetry() {
        PlayerTransferRetryCoordinator staleCoordinator =
                coordinatorWithBootstrapRegistryRemovingTransfer();

        BackendBootstrapReservation existing =
                new BackendBootstrapReservation(
                        "lobby-1",
                        UUID.fromString(
                                "22222222-3333-4444-5555-666666666666"
                        ),
                        OTHER_PLAYER_ID,
                        NOW - 1
                );

        bootstrapRegistry.register(existing);
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );

        staleCoordinator.start(request());

        verify(targetResolver, never()).resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
        verifyNoTransferExecution();
    }

    @Test
    void staleBootstrapRejectionDoesNotSendTerminalResult() {
        PlayerTransferRetryCoordinator staleCoordinator =
                coordinatorWithBootstrapRegistryRemovingTransfer();

        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "lobby-1",
                        UUID.fromString(
                                "22222222-3333-4444-5555-666666666666"
                        ),
                        OTHER_PLAYER_ID,
                        NOW - 1
                )
        );
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );

        staleCoordinator.start(request());

        assertTrue(completions.isEmpty());
        assertTrue(bootstrapRejections.isEmpty());
    }

    @Test
    void staleBootstrapRejectionPreservesNewerTransferState() {
        PlayerTransferRetryCoordinator staleCoordinator =
                coordinatorWithBootstrapRegistryRemovingTransfer();

        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "lobby-1",
                        UUID.fromString(
                                "22222222-3333-4444-5555-666666666666"
                        ),
                        OTHER_PLAYER_ID,
                        NOW - 1
                )
        );
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );

        PendingPlayerTransfer newerTransfer =
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        "skyblock-2",
                        "lobby-2",
                        NOW + 1
                );

        staleCoordinator.start(requestWithStaleBootstrap(newerTransfer));

        assertEquals(
                newerTransfer,
                transferRegistry.findByRequest(REQUEST_ID).orElseThrow()
        );
    }

    @Test
    void targetBusyRetriesAnotherBackend() {
        RegisteredServer secondTarget = server("lobby-2");

        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "lobby-1",
                        UUID.fromString(
                                "22222222-3333-4444-5555-666666666666"
                        ),
                        OTHER_PLAYER_ID,
                        NOW - 1
                )
        );
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(targetResolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(
                TransferTargetResolution
                        .bootstrapRequired(secondTarget)
        );
        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        coordinator.start(request());

        verify(transferExecutor).execute(player, secondTarget);
        assertEquals(
                List.of(PlayerTransferCompletion.success()),
                completions
        );
        assertTrue(bootstrapRejections.isEmpty());
    }

    @Test
    void requestIdConflictDoesNotRetryAnotherBackend() {
        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "lobby-2",
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        NOW - 1
                )
        );
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );

        coordinator.start(request());

        verify(targetResolver, never()).resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
        assertEquals(
                List.of(
                        BackendBootstrapRegistrationResult
                                .REQUEST_ID_CONFLICT
                ),
                bootstrapRejections
        );
    }

    @Test
    void alreadyReservedDoesNotRetryAnotherBackend() {
        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "lobby-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                )
        );
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );

        coordinator.start(request());

        verify(targetResolver, never()).resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
        assertEquals(
                List.of(
                        BackendBootstrapRegistrationResult
                                .ALREADY_RESERVED
                ),
                bootstrapRejections
        );
    }

    private PlayerTransferRetryCoordinator.TransferRetryRequest request() {
        return new PlayerTransferRetryCoordinator.TransferRetryRequest(
                REQUEST_ID,
                PLAYER_ID,
                "skyblock-1",
                BackendType.LOBBY,
                NOW,
                player,
                () -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                bootstrapRejections::add,
                ignored -> {
                },
                completions::add,
                ignored -> {
                }
        );
    }

    private PlayerTransferRetryCoordinator
    coordinatorWithBootstrapRegistryRemovingTransfer() {
        BackendBootstrapRegistry registry =
                mock(BackendBootstrapRegistry.class);

        when(registry.register(any()))
                .thenAnswer(invocation -> {
                    transferRegistry.remove(REQUEST_ID);
                    return BackendBootstrapRegistrationResult.TARGET_BUSY;
                });

        return new PlayerTransferRetryCoordinator(
                registry,
                targetResolver,
                transferRegistry,
                allocationService,
                transferExecutor
        );
    }

    private PlayerTransferRetryCoordinator.TransferRetryRequest
    requestWithStaleBootstrap(PendingPlayerTransfer newerTransfer) {
        return new PlayerTransferRetryCoordinator.TransferRetryRequest(
                REQUEST_ID,
                PLAYER_ID,
                "skyblock-1",
                BackendType.LOBBY,
                NOW,
                player,
                () -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                bootstrapRejections::add,
                ignored -> {
                },
                completions::add,
                ignored -> transferRegistry.register(newerTransfer)
        );
    }

    private void verifyNoTransferExecution() {
        verify(
                transferExecutor,
                never()
        ).execute(
                any(),
                any()
        );
    }

    private RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);

        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);

        return server;
    }
}

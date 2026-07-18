package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapReservation;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyTransferServiceTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final UUID OTHER_REQUEST_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final long NOW =
            1_750_000_000_000L;

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PendingPlayerTransferRegistry transferRegistry;
    private BackendBootstrapRegistry bootstrapRegistry;
    private TransferTargetResolver targetResolver;
    private PlayerTransferExecutor transferExecutor;
    private Queue<UUID> requestIds;
    private Player player;
    private RegisteredServer lobbyTarget;
    private LobbyTransferService service;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        transferRegistry =
                new PendingPlayerTransferRegistry();

        bootstrapRegistry =
                new BackendBootstrapRegistry();

        targetResolver =
                mock(TransferTargetResolver.class);

        transferExecutor =
                mock(PlayerTransferExecutor.class);

        requestIds =
                new ArrayDeque<>();

        requestIds.add(REQUEST_ID);

        player = mock(Player.class);
        lobbyTarget = registeredServer("lobby-1");

        when(player.getUniqueId())
                .thenReturn(PLAYER_ID);

        service =
                new LobbyTransferService(
                        sessionRegistry,
                        transferRegistry,
                        bootstrapRegistry,
                        targetResolver,
                        transferExecutor,
                        Clock.fixed(
                                Instant.ofEpochMilli(NOW),
                                ZoneOffset.UTC
                        ),
                        () -> requestIds.remove()
                );
    }

    @Test
    void rejectsUnauthenticatedPlayer() {
        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService
                        .AUTHENTICATION_REQUIRED_MESSAGE
        );

        verifyNoTransferExecution();
    }

    @Test
    void rejectsPlayerWithoutCurrentServer() {
        authenticatePlayer();

        when(player.getCurrentServer())
                .thenReturn(Optional.empty());

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.NO_CURRENT_SERVER_MESSAGE
        );

        verifyNoTransferExecution();
    }

    @Test
    void rejectsNotConfiguredLobby() {
        configureAuthenticatedPlayerOn("skyblock-1");

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution.notConfigured()
                );

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.LOBBY_UNAVAILABLE_MESSAGE
        );

        verifyNoTransferExecution();
    }

    @Test
    void rejectsNotAuthenticatedLobby() {
        configureAuthenticatedPlayerOn("skyblock-1");

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .notAuthenticated()
                );

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.LOBBY_UNAVAILABLE_MESSAGE
        );

        verifyNoTransferExecution();
    }

    @Test
    void bootstrapRequiredRegistersReservationBeforeExecuting() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenAnswer(invocation -> {
                    assertEquals(
                            REQUEST_ID,
                            bootstrapRegistry
                                    .findByTarget("lobby-1")
                                    .orElseThrow()
                                    .requestId()
                    );
                    return future;
                });

        service.transferToLobby(player);

        assertEquals(
                REQUEST_ID,
                bootstrapRegistry
                        .findByTarget("lobby-1")
                        .orElseThrow()
                        .requestId()
        );

        verify(transferExecutor).execute(
                player,
                lobbyTarget
        );
    }

    @Test
    void rejectsPlayerAlreadyConnectedToResolvedLobby() {
        configureAuthenticatedPlayerOn("lobby-1");
        configureResolvedLobby();

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.ALREADY_IN_LOBBY_MESSAGE
        );

        verifyNoTransferExecution();
    }

    @Test
    void rejectsBusyPlayer() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureResolvedLobby();

        transferRegistry.register(
                new PendingPlayerTransfer(
                        OTHER_REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        "lobby-1",
                        NOW - 100
                )
        );

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_PENDING_MESSAGE
        );

        verifyNoTransferExecution();
    }

    @Test
    void sendsSuccessAndClearsPendingTransfer() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureResolvedLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        service.transferToLobby(player);

        verify(transferExecutor).execute(
                player,
                lobbyTarget
        );

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_SUCCESS_MESSAGE
        );

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );

        assertTrue(
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void resolvedTransferDoesNotCreateBootstrapReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureResolvedLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        service.transferToLobby(player);

        assertEquals(0, bootstrapRegistry.size());
    }

    @Test
    void bootstrapSuccessKeepsReservationForHandshake() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        service.transferToLobby(player);

        assertEquals(
                REQUEST_ID,
                bootstrapRegistry
                        .findByTarget("lobby-1")
                        .orElseThrow()
                        .requestId()
        );

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void bootstrapRejectedRemovesReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.rejected()
                        )
                );

        service.transferToLobby(player);

        assertTrue(
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void bootstrapFailedRemovesReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.failed()
                        )
                );

        service.transferToLobby(player);

        assertTrue(
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void bootstrapTimedOutRemovesReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.timedOut()
                        )
                );

        service.transferToLobby(player);

        assertTrue(
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void bootstrapExecutorExceptionRemovesReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenThrow(
                        new IllegalStateException("internal")
                );

        service.transferToLobby(player);

        assertTrue(
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_FAILED_MESSAGE
        );
    }

    @Test
    void bootstrapExceptionalCompletionRemovesReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(future);

        service.transferToLobby(player);

        future.completeExceptionally(
                new IllegalStateException("internal")
        );

        assertTrue(
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_FAILED_MESSAGE
        );
    }

    @Test
    void bootstrapTargetBusyFailsClosedAndKeepsExistingReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        BackendBootstrapReservation existingReservation =
                new BackendBootstrapReservation(
                        "lobby-1",
                        OTHER_REQUEST_ID,
                        UUID.fromString(
                                "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"
                        ),
                        NOW - 1
                );

        bootstrapRegistry.register(existingReservation);

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.LOBBY_UNAVAILABLE_MESSAGE
        );

        verifyNoTransferExecution();

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );

        assertEquals(
                existingReservation,
                bootstrapRegistry
                        .findByTarget("lobby-1")
                        .orElseThrow()
        );
    }

    @Test
    void bootstrapRequestConflictFailsClosedAndKeepsExistingReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        BackendBootstrapReservation existingReservation =
                new BackendBootstrapReservation(
                        "lobby-2",
                        REQUEST_ID,
                        UUID.fromString(
                                "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"
                        ),
                        NOW - 1
                );

        bootstrapRegistry.register(existingReservation);

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.LOBBY_UNAVAILABLE_MESSAGE
        );

        verifyNoTransferExecution();

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );

        assertEquals(
                existingReservation,
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .orElseThrow()
        );
    }

    @Test
    void bootstrapAlreadyReservedFailsClosedAndKeepsReservation() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        BackendBootstrapReservation existingReservation =
                new BackendBootstrapReservation(
                        "lobby-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                );

        bootstrapRegistry.register(existingReservation);

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.LOBBY_UNAVAILABLE_MESSAGE
        );

        verifyNoTransferExecution();

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );

        assertEquals(
                existingReservation,
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .orElseThrow()
        );
    }

    @Test
    void lateBootstrapResultDoesNotRemoveDifferentState() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureBootstrapRequiredLobby();

        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(future);

        service.transferToLobby(player);

        transferRegistry.remove(REQUEST_ID);
        bootstrapRegistry.removeByRequest(REQUEST_ID);

        PendingPlayerTransfer newerTransfer =
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        UUID.fromString(
                                "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"
                        ),
                        "auth-1",
                        "lobby-2",
                        NOW + 1
                );

        BackendBootstrapReservation newerReservation =
                new BackendBootstrapReservation(
                        "lobby-2",
                        REQUEST_ID,
                        newerTransfer.playerId(),
                        NOW + 1
                );

        transferRegistry.register(newerTransfer);
        bootstrapRegistry.register(newerReservation);

        future.complete(
                PlayerTransferCompletion.failed()
        );

        assertEquals(
                newerTransfer,
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .orElseThrow()
        );

        assertEquals(
                newerReservation,
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .orElseThrow()
        );

        verify(
                player,
                never()
        ).sendMessage(any(Component.class));
    }

    @Test
    void sendsRejectedMessageAndClearsPendingTransfer() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureResolvedLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.rejected()
                        )
                );

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_FAILED_MESSAGE
        );

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void sendsFailedMessageAndClearsPendingTransfer() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureResolvedLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.failed()
                        )
                );

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_FAILED_MESSAGE
        );

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void sendsTimeoutMessageAndClearsPendingTransfer() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureResolvedLobby();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.timedOut()
                        )
                );

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_TIMED_OUT_MESSAGE
        );

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void treatsExecutorExceptionAsFailureAndClearsPending() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureResolvedLobby();

        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(future);

        service.transferToLobby(player);

        future.completeExceptionally(
                new IllegalStateException("internal")
        );

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_FAILED_MESSAGE
        );

        assertTrue(
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void lateResultDoesNotRemoveDifferentTransfer() {
        configureAuthenticatedPlayerOn("skyblock-1");
        configureResolvedLobby();

        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, lobbyTarget))
                .thenReturn(future);

        service.transferToLobby(player);

        transferRegistry.remove(REQUEST_ID);

        PendingPlayerTransfer newerTransfer =
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        UUID.fromString(
                                "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"
                        ),
                        "auth-1",
                        "lobby-1",
                        NOW + 1
                );

        transferRegistry.register(newerTransfer);

        future.complete(
                PlayerTransferCompletion.success()
        );

        assertEquals(
                newerTransfer,
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .orElseThrow()
        );

        verify(
                player,
                never()
        ).sendMessage(any(Component.class));
    }

    private void configureAuthenticatedPlayerOn(
            String serverName
    ) {
        authenticatePlayer();

        ServerConnection connection =
                serverConnection(serverName);

        when(player.getCurrentServer())
                .thenReturn(
                        Optional.of(
                                connection
                        )
                );
    }

    private void authenticatePlayer() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );
    }

    private void configureResolvedLobby() {
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .resolved(lobbyTarget)
                );
    }

    private void configureBootstrapRequiredLobby() {
        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(lobbyTarget)
                );
    }

    private ServerConnection serverConnection(
            String serverName
    ) {
        ServerConnection connection =
                mock(ServerConnection.class);

        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(connection.getServerInfo())
                .thenReturn(serverInfo);

        when(serverInfo.getName())
                .thenReturn(serverName);

        return connection;
    }

    private RegisteredServer registeredServer(
            String serverName
    ) {
        RegisteredServer server =
                mock(RegisteredServer.class);

        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(server.getServerInfo())
                .thenReturn(serverInfo);

        when(serverInfo.getName())
                .thenReturn(serverName);

        return server;
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
}

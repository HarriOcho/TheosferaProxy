package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.TransferRequestPayload;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistrationResult;
import com.theosfera.proxy.transfer.DistributedPlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferRegistrationResult;
import com.theosfera.proxy.transfer.TransferResultSender;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferRequestMessageHandlerTest {

    private static final UUID REQUEST_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );
    private static final UUID PLAYER_ID = UUID.fromString(
            "417e98b4-74a1-467e-b453-a15be3af8996"
    );
    private static final UUID OTHER_PLAYER_ID = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );
    private static final long NOW = 1_750_000_000_000L;

    private ProxyServer proxyServer;
    private BackendIdentityRegistry identityRegistry;
    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerServerPresenceRegistry presenceRegistry;
    private DistributedPlayerTransferRetryCoordinator retryCoordinator;
    private TransferResultSender resultSender;
    private Logger logger;
    private Player player;
    private ServerConnection source;
    private TransferRequestMessageHandler handler;

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);
        identityRegistry = new BackendIdentityRegistry();
        identityRegistry.register(
                new BackendIdentity("lobby-1", BackendType.LOBBY)
        );
        sessionRegistry = new AuthenticatedPlayerSessionRegistry();
        presenceRegistry = new PlayerServerPresenceRegistry(sessionRegistry);
        retryCoordinator = mock(DistributedPlayerTransferRetryCoordinator.class);
        resultSender = mock(TransferResultSender.class);
        logger = mock(Logger.class);
        player = mock(Player.class);
        source = mock(ServerConnection.class);

        ServerInfo sourceInfo = mock(ServerInfo.class);
        when(source.getServerInfo()).thenReturn(sourceInfo);
        when(sourceInfo.getName()).thenReturn("lobby-1");
        when(source.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        Clock clock = Clock.fixed(
                Instant.ofEpochMilli(NOW),
                ZoneOffset.UTC
        );
        handler = new TransferRequestMessageHandler(
                proxyServer,
                identityRegistry,
                sessionRegistry,
                presenceRegistry,
                retryCoordinator,
                resultSender,
                logger,
                clock
        );
    }

    @Test
    void declaresTransferRequestMessageType() {
        assertEquals(
                ProtocolMessageType.TRANSFER_REQUEST,
                handler.messageType()
        );
    }

    @Test
    void delegatesValidTransferToDistributedRetryCoordinator() {
        registerLobbyPlayerState();
        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );

        handler.handle(context);

        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRetryRequest();
        assertEquals(REQUEST_ID, request.requestId());
        assertEquals(PLAYER_ID, request.playerId());
        assertEquals("lobby-1", request.sourceBackendName());
        assertEquals(BackendType.SKYBLOCK, request.targetBackendType());
        assertEquals(NOW, request.requestedAt());
        assertEquals(player, request.player());
        verify(resultSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void successfulDistributedCompletionRemovesOnlyOriginalSourcePresence() {
        registerLobbyPlayerState();
        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );
        handler.handle(context);
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRetryRequest();

        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "skyblock-1",
                        NOW + 100
                )
        );
        request.completionHandler().accept(
                PlayerTransferCompletion.success()
        );

        assertEquals(
                "skyblock-1",
                presenceRegistry.find(PLAYER_ID).orElseThrow().backendName()
        );
        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    @Test
    void failedDistributedCompletionKeepsSourcePresence() {
        registerLobbyPlayerState();
        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );
        handler.handle(context);
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRetryRequest();

        request.completionHandler().accept(
                PlayerTransferCompletion.failed()
        );

        assertEquals(
                "lobby-1",
                presenceRegistry.find(PLAYER_ID).orElseThrow().backendName()
        );
        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.FAILED,
                "Player transfer failed"
        );
    }

    @Test
    void mapsDistributedCapacityCoordinationFailureToRejection() {
        registerLobbyPlayerState();
        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );
        handler.handle(context);

        captureRetryRequest().capacityRejectedHandler().accept(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE
        );

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Backend capacity coordination is unavailable"
        );
    }

    @Test
    void mapsDistributedNoCapacityToRejection() {
        registerLobbyPlayerState();
        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );
        handler.handle(context);

        captureRetryRequest().capacityRejectedHandler().accept(
                BackendCapacityReserveResult.Status.NO_CAPACITY
        );

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Target backend has no available capacity"
        );
    }

    @Test
    void mapsRegistrationBootstrapAndUnavailableCallbacks() {
        registerLobbyPlayerState();
        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );
        handler.handle(context);
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRetryRequest();

        request.registrationRejectedHandler().accept(
                PlayerTransferRegistrationResult.PLAYER_BUSY
        );
        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player already has a pending transfer"
        );

        request.bootstrapRejectedHandler().accept(
                BackendBootstrapRegistrationResult.TARGET_BUSY
        );
        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Target backend bootstrap is already in progress"
        );

        request.unavailableHandler().accept(
                TransferTargetResolution.notAuthenticated()
        );
        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Target backend is not authenticated"
        );
    }

    @Test
    void lateDistributedResultOnlyLogs() {
        registerLobbyPlayerState();
        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );
        handler.handle(context);
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRetryRequest();
        PendingPlayerTransfer transfer = new PendingPlayerTransfer(
                REQUEST_ID,
                PLAYER_ID,
                "lobby-1",
                "skyblock-1",
                NOW
        );

        request.lateResultHandler().accept(transfer);

        verify(logger).warn(
                "Resultado tardio de transferencia ignorado "
                        + "(requestId: {}, playerId: {}).",
                REQUEST_ID,
                PLAYER_ID
        );
        verify(resultSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void rejectsSpoofedPlayerIdentifierBeforeDistributedAllocation() {
        ProtocolMessageContext context = transferContext(
                OTHER_PLAYER_ID,
                BackendType.SKYBLOCK
        );

        handler.handle(context);

        verify(resultSender).send(
                context,
                OTHER_PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Transfer source does not match player"
        );
        verify(retryCoordinator, never()).start(any());
    }

    @Test
    void rejectsUnauthenticatedPlayerBeforeDistributedAllocation() {
        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player is not authenticated"
        );
        verify(retryCoordinator, never()).start(any());
    }

    @Test
    void rejectsPresenceFromDifferentBackendBeforeDistributedAllocation() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );
        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "skyblock-1",
                        NOW - 100
                )
        );

        ProtocolMessageContext context = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );
        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player presence does not match source backend"
        );
        verify(retryCoordinator, never()).start(any());
    }

    @Test
    void transfersAuthenticatedPlayerFromAuthToLobbyWithoutPresenceRequirement() {
        identityRegistry.register(
                new BackendIdentity("auth-1", BackendType.AUTH)
        );
        ServerInfo sourceInfo = source.getServerInfo();
        when(sourceInfo.getName()).thenReturn("auth-1");
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );
        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.of(player));
        when(player.getCurrentServer())
                .thenReturn(Optional.of(source));

        handler.handle(transferContext(PLAYER_ID, BackendType.LOBBY));

        assertEquals(
                BackendType.LOBBY,
                captureRetryRequest().targetBackendType()
        );
    }

    @Test
    void rejectsAuthToSkyblockAndOfflinePlayers() {
        identityRegistry.register(
                new BackendIdentity("auth-1", BackendType.AUTH)
        );
        ServerInfo sourceInfo = source.getServerInfo();
        when(sourceInfo.getName()).thenReturn("auth-1");

        ProtocolMessageContext forbidden = transferContext(
                PLAYER_ID,
                BackendType.SKYBLOCK
        );
        handler.handle(forbidden);
        verify(resultSender).send(
                forbidden,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Transfer is not allowed for source and target backend types"
        );

        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );
        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.empty());
        ProtocolMessageContext offline = transferContext(
                PLAYER_ID,
                BackendType.LOBBY
        );
        handler.handle(offline);
        verify(resultSender).send(
                offline,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player connection does not match source backend"
        );
    }

    @Test
    void rejectsUnexpectedPayloadAndNullContext() {
        ProtocolEnvelope<PingPayload> envelope = ProtocolEnvelope.create(
                ProtocolMessageType.TRANSFER_REQUEST,
                new PingPayload(NOW)
        );
        ProtocolMessageContext context = new ProtocolMessageContext(
                source,
                envelope
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> handler.handle(context)
        );
        assertThrows(
                NullPointerException.class,
                () -> handler.handle(null)
        );
    }

    private DistributedPlayerTransferRetryCoordinator.TransferRetryRequest
    captureRetryRequest() {
        ArgumentCaptor<DistributedPlayerTransferRetryCoordinator.TransferRetryRequest>
                captor = ArgumentCaptor.forClass(
                DistributedPlayerTransferRetryCoordinator
                        .TransferRetryRequest.class
        );
        verify(retryCoordinator).start(captor.capture());
        return captor.getValue();
    }

    private void registerLobbyPlayerState() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );
        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "lobby-1",
                        NOW - 100
                )
        );
        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.of(player));
        when(player.getCurrentServer())
                .thenReturn(Optional.of(source));
    }

    private ProtocolMessageContext transferContext(
            UUID playerId,
            BackendType targetBackendType
    ) {
        ProtocolEnvelope<TransferRequestPayload> envelope =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.TRANSFER_REQUEST,
                        REQUEST_ID,
                        NOW - 1,
                        new TransferRequestPayload(
                                playerId,
                                targetBackendType
                        )
                );
        return new ProtocolMessageContext(source, envelope);
    }
}

package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.TransferRequestPayload;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.TransferResultSender;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferRequestMessageHandlerTest {

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

    private ProxyServer proxyServer;
    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerServerPresenceRegistry presenceRegistry;
    private PendingPlayerTransferRegistry transferRegistry;
    private TransferTargetResolver targetResolver;
    private PlayerTransferExecutor transferExecutor;
    private TransferResultSender resultSender;
    private Logger logger;
    private Player player;
    private ServerConnection source;
    private RegisteredServer target;
    private TransferRequestMessageHandler handler;

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);

        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );

        transferRegistry =
                new PendingPlayerTransferRegistry();

        targetResolver =
                mock(TransferTargetResolver.class);

        transferExecutor =
                mock(PlayerTransferExecutor.class);

        resultSender =
                mock(TransferResultSender.class);

        logger = mock(Logger.class);
        player = mock(Player.class);
        source = mock(ServerConnection.class);
        target = mock(RegisteredServer.class);

        configureSourceConnection();
        configureTarget();

        Clock clock = Clock.fixed(
                Instant.ofEpochMilli(NOW),
                ZoneOffset.UTC
        );

        handler = new TransferRequestMessageHandler(
                proxyServer,
                sessionRegistry,
                presenceRegistry,
                transferRegistry,
                targetResolver,
                transferExecutor,
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
    void transfersAuthenticatedPlayerFromMatchingSource() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(transferExecutor).execute(player, target);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );

        assertFalse(
                transferRegistry
                        .findByPlayer(PLAYER_ID)
                        .isPresent()
        );

        assertFalse(
                presenceRegistry
                        .find(PLAYER_ID)
                        .isPresent()
        );
    }

    @Test
    void rejectsSpoofedPlayerIdentifier() {
        ProtocolMessageContext context =
                transferContext(OTHER_PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                OTHER_PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Transfer source does not match player"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsUnauthenticatedPlayer() {
        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player is not authenticated"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsPresenceFromDifferentBackend() {
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

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player presence does not match source backend"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsTargetWithoutAuthenticatedHandshake() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .notAuthenticated()
                );

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Target backend is not authenticated"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void preservesNewTargetPresenceOnSuccessfulCallback() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "skyblock-1",
                        NOW + 100
                )
        );

        future.complete(
                PlayerTransferCompletion.success()
        );

        assertEquals(
                "skyblock-1",
                presenceRegistry
                        .find(PLAYER_ID)
                        .orElseThrow()
                        .backendName()
        );
    }

    @Test
    void rejectsEnvelopeWithUnexpectedPayload() {
        ProtocolEnvelope<PingPayload> envelope =
                ProtocolEnvelope.create(
                        ProtocolMessageType.TRANSFER_REQUEST,
                        new PingPayload(NOW)
                );

        ProtocolMessageContext context =
                new ProtocolMessageContext(
                        source,
                        envelope
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> handler.handle(context)
        );
    }

    @Test
    void rejectsNullContext() {
        assertThrows(
                NullPointerException.class,
                () -> handler.handle(null)
        );
    }

    private void registerPlayerState() {
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

    private void configureSourceConnection() {
        ServerInfo sourceInfo =
                mock(ServerInfo.class);

        when(source.getServerInfo())
                .thenReturn(sourceInfo);

        when(sourceInfo.getName())
                .thenReturn("lobby-1");

        when(source.getPlayer())
                .thenReturn(player);

        when(player.getUniqueId())
                .thenReturn(PLAYER_ID);
    }

    private void configureTarget() {
        ServerInfo targetInfo =
                mock(ServerInfo.class);

        when(target.getServerInfo())
                .thenReturn(targetInfo);

        when(targetInfo.getName())
                .thenReturn("skyblock-1");
    }

    private ProtocolMessageContext transferContext(
            UUID playerId
    ) {
        ProtocolEnvelope<TransferRequestPayload> envelope =
                new ProtocolEnvelope<>(
                        com.theosfera.protocol.ProtocolVersion.CURRENT,
                        ProtocolMessageType.TRANSFER_REQUEST,
                        REQUEST_ID,
                        NOW - 1,
                        new TransferRequestPayload(
                                playerId,
                                BackendType.SKYBLOCK
                        )
                );

        return new ProtocolMessageContext(
                source,
                envelope
        );
    }
}
package com.theosfera.proxy.messaging;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendHelloPayload;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedPayload;
import com.theosfera.protocol.message.payload.PlayerServerReadyPayload;
import com.theosfera.protocol.message.payload.TransferRequestPayload;
import com.theosfera.protocol.message.payload.TransferResultPayload;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.theosfera.proxy.messaging.handler.BackendHelloMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerAuthenticatedMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerServerReadyMessageHandler;
import com.theosfera.proxy.messaging.handler.TransferRequestMessageHandler;
import com.theosfera.proxy.session.PlayerAuthenticationAckSender;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.TransferResultSender;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolPlayerTransferFlowTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private static final UUID TRANSFER_REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    @Test
    void authenticatesAuthorizesAndTransfersPlayer() {
        Logger logger = mock(Logger.class);
        ProxyServer proxyServer = mock(ProxyServer.class);

        ProtocolMessageSender messageSender =
                mock(ProtocolMessageSender.class);

        PlayerTransferExecutor transferExecutor =
                mock(PlayerTransferExecutor.class);

        BackendAuthorizationPolicy policy =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "auth-1",
                                BackendType.AUTH,
                                "lobby-1",
                                BackendType.LOBBY,
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                );

        BackendIdentityRegistry identityRegistry =
                new BackendIdentityRegistry();

        BackendHealthRegistry healthRegistry =
                new BackendHealthRegistry(
                        Clock.systemUTC(),
                        Duration.ofSeconds(15)
                );

        AuthenticatedPlayerSessionRegistry sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        PlayerServerPresenceRegistry presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );

        PendingPlayerTransferRegistry transferRegistry =
                new PendingPlayerTransferRegistry();

        BackendBootstrapRegistry bootstrapRegistry =
                new BackendBootstrapRegistry();

        Player player = mock(Player.class);

        ServerConnection authSource =
                serverConnection("auth-1");

        ServerConnection lobbySource =
                serverConnection("lobby-1");

        ServerConnection skyblockSource =
                serverConnection("skyblock-1");

        RegisteredServer skyblockTarget =
                registeredServer("skyblock-1");

        when(authSource.getPlayer()).thenReturn(player);
        when(lobbySource.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getUsername()).thenReturn("HarriOcho");

        when(player.getCurrentServer())
                .thenReturn(Optional.of(lobbySource));

        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.of(player));

        when(proxyServer.getServer("skyblock-1"))
                .thenReturn(Optional.of(skyblockTarget));

        when(skyblockTarget.getPlayersConnected())
                .thenReturn(List.of(mock(Player.class)));

        when(messageSender.send(
                any(ServerConnection.class),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        when(transferExecutor.execute(
                player,
                skyblockTarget
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerTransferCompletion.success()
                )
        );

        TransferTargetResolver targetResolver =
                new TransferTargetResolver(
                        proxyServer,
                        policy,
                        identityRegistry,
                        healthRegistry
                );

        TransferResultSender resultSender =
                new TransferResultSender(
                        messageSender,
                        logger
                );

        PlayerAuthenticationAckSender
                authenticationAckSender =
                new PlayerAuthenticationAckSender(
                        messageSender,
                        logger
                );

        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(
                                new BackendHelloMessageHandler(
                                        policy,
                                        identityRegistry,
                                        bootstrapRegistry,
                                        messageSender,
                                        logger
                                ),
                                new PlayerAuthenticatedMessageHandler(
                                        sessionRegistry,
                                        authenticationAckSender,
                                        logger
                                ),
                                new PlayerServerReadyMessageHandler(
                                        presenceRegistry,
                                        logger
                                ),
                                new TransferRequestMessageHandler(
                                        proxyServer,
                                        identityRegistry,
                                        sessionRegistry,
                                        presenceRegistry,
                                        transferRegistry,
                                        bootstrapRegistry,
                                        targetResolver,
                                        transferExecutor,
                                        resultSender,
                                        logger
                                )
                        )
                );

        ProtocolMessageListener listener =
                new ProtocolMessageListener(
                        logger,
                        new BackendMessageAuthorizer(
                                identityRegistry
                        ),
                        dispatcher
                );

        send(
                listener,
                authSource,
                ProtocolEnvelope.create(
                        ProtocolMessageType.BACKEND_HELLO,
                        new BackendHelloPayload(
                                "auth-1",
                                BackendType.AUTH
                        )
                )
        );

        send(
                listener,
                lobbySource,
                ProtocolEnvelope.create(
                        ProtocolMessageType.BACKEND_HELLO,
                        new BackendHelloPayload(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                )
        );

        send(
                listener,
                skyblockSource,
                ProtocolEnvelope.create(
                        ProtocolMessageType.BACKEND_HELLO,
                        new BackendHelloPayload(
                                "skyblock-1",
                                BackendType.SKYBLOCK
                        )
                )
        );

        healthRegistry.markHealthy("skyblock-1");

        send(
                listener,
                authSource,
                ProtocolEnvelope.create(
                        ProtocolMessageType.PLAYER_AUTHENTICATED,
                        new PlayerAuthenticatedPayload(
                                PLAYER_ID,
                                "HarriOcho",
                                1_000L
                        )
                )
        );

        send(
                listener,
                lobbySource,
                ProtocolEnvelope.create(
                        ProtocolMessageType.PLAYER_SERVER_READY,
                        new PlayerServerReadyPayload(
                                PLAYER_ID,
                                "lobby-1",
                                2_000L
                        )
                )
        );

        ProtocolEnvelope<TransferRequestPayload>
                transferRequest =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.TRANSFER_REQUEST,
                        TRANSFER_REQUEST_ID,
                        3_000L,
                        new TransferRequestPayload(
                                PLAYER_ID,
                                BackendType.SKYBLOCK
                        )
                );

        PluginMessageEvent transferEvent =
                send(
                        listener,
                        lobbySource,
                        transferRequest
                );

        assertFalse(
                transferEvent.getResult().isAllowed()
        );

        verify(transferExecutor).execute(
                player,
                skyblockTarget
        );

        assertTrue(
                transferRegistry.snapshotByPlayer().isEmpty()
        );

        assertTrue(
                presenceRegistry.find(PLAYER_ID).isEmpty()
        );

        ArgumentCaptor<ProtocolEnvelope<?>> envelopeCaptor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(
                messageSender,
                atLeastOnce()
        ).send(
                any(ServerConnection.class),
                envelopeCaptor.capture()
        );

        ProtocolEnvelope<?> transferResult =
                envelopeCaptor
                        .getAllValues()
                        .stream()
                        .filter(envelope ->
                                ProtocolMessageType
                                        .TRANSFER_RESULT
                                        .equals(envelope.type())
                        )
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                TRANSFER_REQUEST_ID,
                transferResult.requestId()
        );

        TransferResultPayload resultPayload =
                (TransferResultPayload)
                        transferResult.payload();

        assertEquals(PLAYER_ID, resultPayload.playerId());

        assertEquals(
                TransferResultStatus.SUCCESS,
                resultPayload.status()
        );

        assertEquals(
                "Player transferred successfully",
                resultPayload.message()
        );
    }

    private PluginMessageEvent send(
            ProtocolMessageListener listener,
            ServerConnection source,
            ProtocolEnvelope<?> envelope
    ) {
        byte[] encoded =
                new ProtocolJsonCodec().encode(envelope);

        PluginMessageEvent event =
                new PluginMessageEvent(
                        source,
                        mock(ChannelMessageSink.class),
                        ProtocolChannel.IDENTIFIER,
                        encoded
                );

        listener.onPluginMessage(event);
        return event;
    }

    private ServerConnection serverConnection(
            String serverName
    ) {
        ServerConnection connection =
                mock(ServerConnection.class);

        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);

        when(connection.getServerInfo())
                .thenReturn(serverInfo);

        return connection;
    }

    private RegisteredServer registeredServer(
            String serverName
    ) {
        RegisteredServer server =
                mock(RegisteredServer.class);

        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);

        when(server.getServerInfo())
                .thenReturn(serverInfo);

        return server;
    }
}

package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedAckPayload;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedPayload;
import com.theosfera.protocol.message.payload.PlayerServerReadyPayload;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.theosfera.proxy.backend.MutableBackendIdentityProvider;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.local.LocalPlayerSessionCoordinator;
import com.theosfera.proxy.messaging.handler.PlayerAuthenticatedMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerServerReadyMessageHandler;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerAuthenticationAckSender;
import com.theosfera.proxy.session.PlayerDisconnectListener;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.theosfera.proxy.session.PlayerSessionReleaseService;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolPlayerSessionFlowTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final ProxyInstanceIdentity PROXY_IDENTITY =
            new ProxyInstanceIdentity(
                    "proxy-session-flow",
                    UUID.fromString(
                            "c76d77c1-c14e-4898-b2ea-a68936ac6265"
                    )
            );

    @Test
    void authenticatesTracksReadyBackendAndCleansOnDisconnect() {
        Logger logger = mock(Logger.class);

        ProtocolMessageSender sender =
                mock(ProtocolMessageSender.class);

        MutableBackendIdentityProvider identityProvider =
                new MutableBackendIdentityProvider();
        identityProvider.register(
                new BackendIdentity(
                        "auth-1",
                        BackendType.AUTH
                )
        );
        identityProvider.register(
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        );

        AuthenticatedPlayerSessionRegistry sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        PlayerSessionCoordinator sessionCoordinator =
                new LocalPlayerSessionCoordinator(
                        sessionRegistry
                );

        PlayerSessionLeaseBindingRegistry leaseBindingRegistry =
                new PlayerSessionLeaseBindingRegistry();

        PlayerServerPresenceRegistry presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );

        PendingPlayerTransferRegistry transferRegistry =
                new PendingPlayerTransferRegistry();

        BackendMessageAuthorizer authorizer =
                new BackendMessageAuthorizer(
                        identityProvider
                );

        PlayerAuthenticationAckSender acknowledgementSender =
                new PlayerAuthenticationAckSender(
                        sender,
                        logger
                );

        PlayerSessionReleaseService releaseService =
                new PlayerSessionReleaseService(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        (key, timeout) -> () -> {
                        },
                        logger
                );

        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(
                                new PlayerAuthenticatedMessageHandler(
                                        sessionCoordinator,
                                        leaseBindingRegistry,
                                        PROXY_IDENTITY,
                                        acknowledgementSender,
                                        (key, timeout) -> () -> {
                                        },
                                        releaseService,
                                        logger
                                ),
                                new PlayerServerReadyMessageHandler(
                                        presenceRegistry,
                                        logger
                                )
                        )
                );

        ProtocolMessageListener listener =
                new ProtocolMessageListener(
                        logger,
                        authorizer,
                        dispatcher
                );

        PlayerDisconnectListener disconnectListener =
                new PlayerDisconnectListener(
                        leaseBindingRegistry,
                        presenceRegistry,
                        transferRegistry,
                        sessionRegistry,
                        releaseService,
                        logger
                );

        Player player = mock(Player.class);

        when(player.getUniqueId())
                .thenReturn(PLAYER_ID);

        when(player.getUsername())
                .thenReturn("HarriOcho");

        ServerConnection authSource =
                createServerConnection(
                        "auth-1",
                        player
                );

        ServerConnection lobbySource =
                createServerConnection(
                        "lobby-1",
                        player
                );

        when(sender.send(
                any(ServerConnection.class),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        ProtocolEnvelope<PlayerAuthenticatedPayload>
                authenticationRequest =
                ProtocolEnvelope.create(
                        ProtocolMessageType
                                .PLAYER_AUTHENTICATED,
                        new PlayerAuthenticatedPayload(
                                PLAYER_ID,
                                "HarriOcho",
                                1_000L
                        )
                );

        PluginMessageEvent authenticatedEvent =
                send(
                        listener,
                        authSource,
                        authenticationRequest
                );

        assertFalse(
                authenticatedEvent.getResult().isAllowed()
        );

        AuthenticatedPlayerSession session =
                sessionRegistry
                        .find(PLAYER_ID)
                        .orElseThrow();

        assertEquals(
                "HarriOcho",
                session.playerName()
        );

        assertEquals(
                1_000L,
                session.authenticatedAt()
        );

        ArgumentCaptor<ProtocolEnvelope<?>> envelopeCaptor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(sender).send(
                any(ServerConnection.class),
                envelopeCaptor.capture()
        );

        ProtocolEnvelope<?> acknowledgement =
                envelopeCaptor.getValue();

        assertEquals(
                ProtocolMessageType
                        .PLAYER_AUTHENTICATED_ACK,
                acknowledgement.type()
        );

        assertEquals(
                authenticationRequest.requestId(),
                acknowledgement.requestId()
        );

        PlayerAuthenticatedAckPayload acknowledgementPayload =
                (PlayerAuthenticatedAckPayload)
                        acknowledgement.payload();

        assertEquals(
                PLAYER_ID,
                acknowledgementPayload.playerId()
        );

        assertTrue(
                acknowledgementPayload.accepted()
        );

        PluginMessageEvent readyEvent =
                send(
                        listener,
                        lobbySource,
                        ProtocolEnvelope.create(
                                ProtocolMessageType
                                        .PLAYER_SERVER_READY,
                                new PlayerServerReadyPayload(
                                        PLAYER_ID,
                                        "lobby-1",
                                        2_000L
                                )
                        )
                );

        assertFalse(
                readyEvent.getResult().isAllowed()
        );

        PlayerServerPresence presence =
                presenceRegistry
                        .find(PLAYER_ID)
                        .orElseThrow();

        assertEquals(
                "lobby-1",
                presence.backendName()
        );

        assertEquals(
                2_000L,
                presence.readyAt()
        );

        DisconnectEvent disconnectEvent =
                mock(DisconnectEvent.class);

        when(disconnectEvent.getPlayer())
                .thenReturn(player);

        disconnectListener.onDisconnect(
                disconnectEvent
        );

        assertFalse(
                sessionRegistry
                        .find(PLAYER_ID)
                        .isPresent()
        );

        assertFalse(
                presenceRegistry
                        .find(PLAYER_ID)
                        .isPresent()
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

    private ServerConnection createServerConnection(
            String serverName,
            Player player
    ) {
        ServerConnection source =
                mock(ServerConnection.class);

        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(serverInfo.getName())
                .thenReturn(serverName);

        when(source.getServerInfo())
                .thenReturn(serverInfo);

        when(source.getPlayer())
                .thenReturn(player);

        return source;
    }
}

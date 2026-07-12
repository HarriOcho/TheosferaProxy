package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendHelloPayload;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedPayload;
import com.theosfera.protocol.message.payload.PlayerServerReadyPayload;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.theosfera.proxy.messaging.handler.BackendHelloMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerAuthenticatedMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerServerReadyMessageHandler;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerDisconnectListener;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProtocolPlayerSessionFlowTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    @Test
    void authenticatesTracksReadyBackendAndCleansOnDisconnect() {
        Logger logger = mock(Logger.class);
        ProtocolMessageSender sender =
                mock(ProtocolMessageSender.class);

        BackendAuthorizationPolicy policy =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "auth-1",
                                BackendType.AUTH,
                                "lobby-1",
                                BackendType.LOBBY
                        )
                );

        BackendIdentityRegistry identityRegistry =
                new BackendIdentityRegistry();

        AuthenticatedPlayerSessionRegistry sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        PlayerServerPresenceRegistry presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );

        BackendMessageAuthorizer authorizer =
                new BackendMessageAuthorizer(
                        identityRegistry
                );

        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(
                                new BackendHelloMessageHandler(
                                        policy,
                                        identityRegistry,
                                        sender,
                                        logger
                                ),
                                new PlayerAuthenticatedMessageHandler(
                                        sessionRegistry,
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
                        sessionRegistry,
                        presenceRegistry,
                        logger
                );

        ServerConnection authSource =
                createServerConnection("auth-1");

        ServerConnection lobbySource =
                createServerConnection("lobby-1");

        when(sender.send(
                any(ServerConnection.class),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

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

        PluginMessageEvent authenticatedEvent =
                send(
                        listener,
                        authSource,
                        ProtocolEnvelope.create(
                                ProtocolMessageType
                                        .PLAYER_AUTHENTICATED,
                                new PlayerAuthenticatedPayload(
                                        PLAYER_ID,
                                        "HarriOcho",
                                        1_000L
                                )
                        )
                );

        assertFalse(
                authenticatedEvent.getResult().isAllowed()
        );

        AuthenticatedPlayerSession session =
                sessionRegistry.find(PLAYER_ID).orElseThrow();

        assertEquals("HarriOcho", session.playerName());
        assertEquals(1_000L, session.authenticatedAt());

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

        assertFalse(readyEvent.getResult().isAllowed());

        PlayerServerPresence presence =
                presenceRegistry.find(PLAYER_ID).orElseThrow();

        assertEquals("lobby-1", presence.backendName());
        assertEquals(2_000L, presence.readyAt());

        Player player = mock(Player.class);
        DisconnectEvent disconnectEvent =
                mock(DisconnectEvent.class);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(disconnectEvent.getPlayer()).thenReturn(player);

        disconnectListener.onDisconnect(
                disconnectEvent
        );

        assertFalse(
                sessionRegistry.find(PLAYER_ID).isPresent()
        );
        assertFalse(
                presenceRegistry.find(PLAYER_ID).isPresent()
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
            String serverName
    ) {
        ServerConnection source =
                mock(ServerConnection.class);
        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);
        when(source.getServerInfo()).thenReturn(serverInfo);

        return source;
    }
}
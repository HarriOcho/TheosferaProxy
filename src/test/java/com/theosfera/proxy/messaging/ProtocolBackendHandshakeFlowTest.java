package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendHelloAckPayload;
import com.theosfera.protocol.message.payload.BackendHelloPayload;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.messaging.handler.BackendHelloMessageHandler;
import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolBackendHandshakeFlowTest {

    @Test
    void receivesHelloRegistersIdentityAndSendsAck() {
        Logger logger = mock(Logger.class);
        ProtocolMessageSender sender =
                mock(ProtocolMessageSender.class);

        BackendAuthorizationPolicy policy =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                );

        BackendIdentityRegistry registry =
                new BackendIdentityRegistry();

        BackendMessageAuthorizer authorizer =
                new BackendMessageAuthorizer(registry);

        BackendHelloMessageHandler helloHandler =
                new BackendHelloMessageHandler(
                        policy,
                        registry,
                        new BackendBootstrapRegistry(),
                        sender,
                        logger
                );


        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(helloHandler)
                );

        ProtocolMessageListener listener =
                new ProtocolMessageListener(
                        logger,
                        authorizer,
                        dispatcher
                );

        ServerConnection source =
                createServerConnection("lobby-1");

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        ProtocolEnvelope<BackendHelloPayload> hello =
                ProtocolEnvelope.create(
                        ProtocolMessageType.BACKEND_HELLO,
                        new BackendHelloPayload(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                );

        byte[] encodedHello =
                new ProtocolJsonCodec().encode(hello);

        PluginMessageEvent event = new PluginMessageEvent(
                source,
                mock(ChannelMessageSink.class),
                ProtocolChannel.IDENTIFIER,
                encodedHello
        );

        listener.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());

        BackendIdentity identity =
                registry.find("lobby-1").orElseThrow();

        assertEquals(
                BackendType.LOBBY,
                identity.backendType()
        );

        ArgumentCaptor<ProtocolEnvelope<?>> captor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(sender).send(
                eq(source),
                captor.capture()
        );

        ProtocolEnvelope<?> acknowledgement =
                captor.getValue();

        assertEquals(
                ProtocolMessageType.BACKEND_HELLO_ACK,
                acknowledgement.type()
        );
        assertEquals(
                hello.requestId(),
                acknowledgement.requestId()
        );

        BackendHelloAckPayload payload =
                (BackendHelloAckPayload)
                        acknowledgement.payload();

        assertTrue(payload.accepted());
        assertEquals(
                "Backend registered",
                payload.message()
        );
    }

    private ServerConnection createServerConnection(
            String serverName
    ) {
        ServerConnection source =
                mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);
        when(source.getServerInfo()).thenReturn(serverInfo);

        return source;
    }
}

package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PongPayload;
import com.theosfera.proxy.messaging.handler.PingMessageHandler;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolHeartbeatFlowTest {

    private static final long PING_SENT_AT =
            1_750_000_000_000L;

    @Test
    void receivesPingAndSendsCorrelatedPong() {
        Logger logger = mock(Logger.class);
        ProtocolMessageSender sender =
                mock(ProtocolMessageSender.class);

        PingMessageHandler pingHandler =
                new PingMessageHandler(sender, logger);

        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(pingHandler)
                );

        ProtocolMessageListener listener =
                new ProtocolMessageListener(
                        logger,
                        dispatcher
                );

        ServerConnection source =
                createServerConnection("lobby-1");

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        ProtocolEnvelope<PingPayload> ping =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PING,
                        new PingPayload(PING_SENT_AT)
                );

        byte[] encodedPing =
                new ProtocolJsonCodec().encode(ping);

        PluginMessageEvent event = new PluginMessageEvent(
                source,
                mock(ChannelMessageSink.class),
                ProtocolChannel.IDENTIFIER,
                encodedPing
        );

        listener.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());

        ArgumentCaptor<ProtocolEnvelope<?>> captor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(sender).send(
                eq(source),
                captor.capture()
        );

        ProtocolEnvelope<?> pong = captor.getValue();

        assertEquals(
                ProtocolMessageType.PONG,
                pong.type()
        );
        assertEquals(
                ping.requestId(),
                pong.requestId()
        );

        PongPayload payload =
                (PongPayload) pong.payload();

        assertEquals(
                PING_SENT_AT,
                payload.pingSentAt()
        );
        assertTrue(
                payload.respondedAt() >= PING_SENT_AT
        );
        assertEquals(
                payload.respondedAt(),
                pong.timestamp()
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
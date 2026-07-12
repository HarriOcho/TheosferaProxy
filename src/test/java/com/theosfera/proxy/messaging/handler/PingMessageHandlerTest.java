package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PongPayload;
import com.theosfera.protocol.message.payload.TransferRequestPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PingMessageHandlerTest {

    private static final long PING_SENT_AT =
            1_750_000_000_000L;
    private static final long RESPONDED_AT =
            PING_SENT_AT + 25;

    private final ProtocolMessageSender sender =
            mock(ProtocolMessageSender.class);
    private final Logger logger = mock(Logger.class);

    private final Clock clock = Clock.fixed(
            Instant.ofEpochMilli(RESPONDED_AT),
            ZoneOffset.UTC
    );

    private final PingMessageHandler handler =
            new PingMessageHandler(
                    sender,
                    logger,
                    clock
            );

    @Test
    void declaresPingMessageType() {
        assertEquals(
                ProtocolMessageType.PING,
                handler.messageType()
        );
    }

    @Test
    void sendsCorrelatedPongResponse() {
        ServerConnection source =
                createServerConnection("lobby-1");

        ProtocolEnvelope<PingPayload> request =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PING,
                        new PingPayload(PING_SENT_AT)
                );

        when(sender.send(
                any(ServerConnection.class),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        ArgumentCaptor<ProtocolEnvelope<?>> captor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(sender).send(
                org.mockito.ArgumentMatchers.eq(source),
                captor.capture()
        );

        ProtocolEnvelope<?> response = captor.getValue();

        assertEquals(
                ProtocolVersion.CURRENT,
                response.version()
        );
        assertEquals(
                ProtocolMessageType.PONG,
                response.type()
        );
        assertEquals(
                request.requestId(),
                response.requestId()
        );
        assertEquals(
                RESPONDED_AT,
                response.timestamp()
        );

        PongPayload payload =
                (PongPayload) response.payload();

        assertEquals(PING_SENT_AT, payload.pingSentAt());
        assertEquals(RESPONDED_AT, payload.respondedAt());
    }

    @Test
    void usesPingTimestampWhenClockIsBehind() {
        Clock behindClock = Clock.fixed(
                Instant.ofEpochMilli(PING_SENT_AT - 25),
                ZoneOffset.UTC
        );

        PingMessageHandler behindHandler =
                new PingMessageHandler(
                        sender,
                        logger,
                        behindClock
                );

        ProtocolEnvelope<PingPayload> request =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PING,
                        new PingPayload(PING_SENT_AT)
                );

        when(sender.send(
                any(ServerConnection.class),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        behindHandler.handle(
                new ProtocolMessageContext(
                        mock(ServerConnection.class),
                        request
                )
        );

        ArgumentCaptor<ProtocolEnvelope<?>> captor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(sender).send(
                any(ServerConnection.class),
                captor.capture()
        );

        PongPayload payload =
                (PongPayload) captor.getValue().payload();

        assertEquals(PING_SENT_AT, payload.respondedAt());
    }

    @Test
    void logsWhenVelocityRejectsPong() {
        ServerConnection source =
                createServerConnection("skyblock-1");

        ProtocolEnvelope<PingPayload> request =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PING,
                        new PingPayload(PING_SENT_AT)
                );

        when(sender.send(
                any(ServerConnection.class),
                any(ProtocolEnvelope.class)
        )).thenReturn(false);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        verify(logger).warn(
                "No se pudo enviar PONG al backend {} "
                        + "(requestId: {}).",
                "skyblock-1",
                request.requestId()
        );
    }

    @Test
    void rejectsIncorrectPayloadType() {
        ProtocolEnvelope<TransferRequestPayload> envelope =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PING,
                        new TransferRequestPayload(
                                UUID.randomUUID(),
                                BackendType.LOBBY
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> handler.handle(
                        new ProtocolMessageContext(
                                mock(ServerConnection.class),
                                envelope
                        )
                )
        );
    }

    @Test
    void rejectsNullContext() {
        assertThrows(
                NullPointerException.class,
                () -> handler.handle(null)
        );
    }

    @Test
    void rejectsNullDependencies() {
        assertThrows(
                NullPointerException.class,
                () -> new PingMessageHandler(
                        null,
                        logger,
                        clock
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PingMessageHandler(
                        sender,
                        null,
                        clock
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PingMessageHandler(
                        sender,
                        logger,
                        null
                )
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
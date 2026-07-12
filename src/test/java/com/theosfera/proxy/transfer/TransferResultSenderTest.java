package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.TransferResultPayload;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferResultSenderTest {

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private static final long TIMESTAMP =
            1_750_000_000_000L;

    private ProtocolMessageSender messageSender;
    private Logger logger;
    private ServerConnection source;
    private ProtocolMessageContext context;
    private TransferResultSender resultSender;

    @BeforeEach
    void setUp() {
        messageSender = mock(ProtocolMessageSender.class);
        logger = mock(Logger.class);
        source = mock(ServerConnection.class);

        ServerInfo serverInfo = mock(ServerInfo.class);

        when(source.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn("lobby-1");

        ProtocolEnvelope<Object> request =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.TRANSFER_REQUEST,
                        REQUEST_ID,
                        TIMESTAMP - 1,
                        new Object()
                );

        context = new ProtocolMessageContext(
                source,
                request
        );

        Clock clock = Clock.fixed(
                Instant.ofEpochMilli(TIMESTAMP),
                ZoneOffset.UTC
        );

        resultSender = new TransferResultSender(
                messageSender,
                logger,
                clock
        );
    }

    @Test
    void sendsCorrelatedTransferResult() {
        when(messageSender.send(
                org.mockito.ArgumentMatchers.eq(source),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);

        assertTrue(
                resultSender.send(
                        context,
                        PLAYER_ID,
                        TransferResultStatus.SUCCESS,
                        "Player transferred successfully"
                )
        );

        ArgumentCaptor<ProtocolEnvelope<?>> envelopeCaptor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(messageSender).send(
                org.mockito.ArgumentMatchers.eq(source),
                envelopeCaptor.capture()
        );

        ProtocolEnvelope<?> envelope =
                envelopeCaptor.getValue();

        assertEquals(
                ProtocolMessageType.TRANSFER_RESULT,
                envelope.type()
        );

        assertEquals(REQUEST_ID, envelope.requestId());
        assertEquals(TIMESTAMP, envelope.timestamp());

        TransferResultPayload payload =
                (TransferResultPayload) envelope.payload();

        assertEquals(PLAYER_ID, payload.playerId());

        assertEquals(
                TransferResultStatus.SUCCESS,
                payload.status()
        );

        assertEquals(
                "Player transferred successfully",
                payload.message()
        );
    }

    @Test
    void reportsRejectedPluginMessageSend() {
        when(messageSender.send(
                org.mockito.ArgumentMatchers.eq(source),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(false);

        assertFalse(
                resultSender.send(
                        context,
                        PLAYER_ID,
                        TransferResultStatus.FAILED,
                        "Player transfer failed"
                )
        );

        verify(logger).warn(
                "No se pudo enviar TRANSFER_RESULT a {} "
                        + "(requestId: {}, playerId: {}, "
                        + "status: {}).",
                "lobby-1",
                REQUEST_ID,
                PLAYER_ID,
                TransferResultStatus.FAILED
        );
    }

    @Test
    void rejectsNullConstructorArgumentsAndContext() {
        assertThrows(
                NullPointerException.class,
                () -> new TransferResultSender(
                        null,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new TransferResultSender(
                        messageSender,
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> resultSender.send(
                        null,
                        PLAYER_ID,
                        TransferResultStatus.SUCCESS,
                        "Player transferred successfully"
                )
        );
    }
}

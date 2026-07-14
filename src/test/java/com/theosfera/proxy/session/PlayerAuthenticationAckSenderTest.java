package com.theosfera.proxy.session;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedAckPayload;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerAuthenticationAckSenderTest {

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
    private PlayerAuthenticationAckSender
            acknowledgementSender;

    @BeforeEach
    void setUp() {
        messageSender = mock(ProtocolMessageSender.class);
        logger = mock(Logger.class);
        source = mock(ServerConnection.class);

        ServerInfo serverInfo = mock(ServerInfo.class);

        when(source.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn("auth-1");

        ProtocolEnvelope<Object> request =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.PLAYER_AUTHENTICATED,
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

        acknowledgementSender =
                new PlayerAuthenticationAckSender(
                        messageSender,
                        logger,
                        clock
                );
    }

    @Test
    void sendsCorrelatedAuthenticationAcknowledgement() {
        when(messageSender.send(
                eq(source),
                any()
        )).thenReturn(true);

        assertTrue(
                acknowledgementSender.send(
                        context,
                        PLAYER_ID,
                        true,
                        "Player session registered"
                )
        );

        ArgumentCaptor<ProtocolEnvelope<?>> envelopeCaptor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(messageSender).send(
                eq(source),
                envelopeCaptor.capture()
        );

        ProtocolEnvelope<?> envelope =
                envelopeCaptor.getValue();

        assertEquals(
                ProtocolMessageType.PLAYER_AUTHENTICATED_ACK,
                envelope.type()
        );

        assertEquals(REQUEST_ID, envelope.requestId());
        assertEquals(TIMESTAMP, envelope.timestamp());

        PlayerAuthenticatedAckPayload payload =
                (PlayerAuthenticatedAckPayload)
                        envelope.payload();

        assertEquals(PLAYER_ID, payload.playerId());
        assertTrue(payload.accepted());

        assertEquals(
                "Player session registered",
                payload.message()
        );
    }

    @Test
    void sendsRejectedAcknowledgement() {
        when(messageSender.send(
                eq(source),
                any()
        )).thenReturn(true);

        assertTrue(
                acknowledgementSender.send(
                        context,
                        PLAYER_ID,
                        false,
                        "Player identity mismatch"
                )
        );

        ArgumentCaptor<ProtocolEnvelope<?>> envelopeCaptor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(messageSender).send(
                eq(source),
                envelopeCaptor.capture()
        );

        PlayerAuthenticatedAckPayload payload =
                (PlayerAuthenticatedAckPayload)
                        envelopeCaptor
                                .getValue()
                                .payload();

        assertEquals(PLAYER_ID, payload.playerId());
        assertFalse(payload.accepted());

        assertEquals(
                "Player identity mismatch",
                payload.message()
        );
    }

    @Test
    void reportsRejectedPluginMessageSend() {
        when(messageSender.send(
                eq(source),
                any()
        )).thenReturn(false);

        assertFalse(
                acknowledgementSender.send(
                        context,
                        PLAYER_ID,
                        false,
                        "Player session conflict"
                )
        );

        verify(logger).warn(
                "No se pudo enviar "
                        + "PLAYER_AUTHENTICATED_ACK a {} "
                        + "(requestId: {}, playerId: {}, "
                        + "accepted: {}).",
                "auth-1",
                REQUEST_ID,
                PLAYER_ID,
                false
        );
    }

    @Test
    void rejectsNullConstructorArgumentsAndContext() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticationAckSender(
                        null,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticationAckSender(
                        messageSender,
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticationAckSender(
                        messageSender,
                        logger,
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> acknowledgementSender.send(
                        null,
                        PLAYER_ID,
                        true,
                        "Player session registered"
                )
        );
    }
}

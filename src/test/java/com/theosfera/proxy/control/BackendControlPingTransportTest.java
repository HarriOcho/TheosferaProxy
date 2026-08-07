package com.theosfera.proxy.control;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.proxy.backend.BackendIdentity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendControlPingTransportTest {

    @Test
    void returnsFalseWithoutAuthenticatedControlSession()
            throws Exception {
        BackendControlMessageSender sender =
                mock(BackendControlMessageSender.class);
        BackendControlPingTransport transport =
                new BackendControlPingTransport(sender);

        when(sender.findSession("lobby-1"))
                .thenReturn(Optional.empty());

        assertFalse(
                transport.send(
                        "lobby-1",
                        pingEnvelope()
                )
        );
    }

    @Test
    void sendsThroughCurrentAuthenticatedSession()
            throws Exception {
        BackendControlMessageSender sender =
                mock(BackendControlMessageSender.class);
        BackendControlPingTransport transport =
                new BackendControlPingTransport(sender);
        BackendControlSession session = session();
        ProtocolEnvelope<PingPayload> envelope = pingEnvelope();

        when(sender.findSession("lobby-1"))
                .thenReturn(Optional.of(session));
        when(sender.send(session, envelope))
                .thenReturn(true);

        assertTrue(transport.send("lobby-1", envelope));

        verify(sender).send(
                eq(session),
                eq(envelope)
        );
    }

    @Test
    void propagatesIoFailureFromBoundSession() throws Exception {
        BackendControlMessageSender sender =
                mock(BackendControlMessageSender.class);
        BackendControlPingTransport transport =
                new BackendControlPingTransport(sender);
        BackendControlSession session = session();
        ProtocolEnvelope<PingPayload> envelope = pingEnvelope();
        IOException failure = new IOException("broken socket");

        when(sender.findSession("lobby-1"))
                .thenReturn(Optional.of(session));
        when(sender.send(session, envelope))
                .thenThrow(failure);

        IOException thrown = assertThrows(
                IOException.class,
                () -> transport.send("lobby-1", envelope)
        );

        assertTrue(thrown == failure);
    }

    private BackendControlSession session() {
        return new BackendControlSession(
                UUID.randomUUID(),
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                ),
                1L
        );
    }

    private ProtocolEnvelope<PingPayload> pingEnvelope() {
        long sentAt = 1_000L;
        return new ProtocolEnvelope<>(
                ProtocolVersion.CURRENT,
                ProtocolMessageType.PING,
                UUID.randomUUID(),
                sentAt,
                new PingPayload(sentAt)
        );
    }
}

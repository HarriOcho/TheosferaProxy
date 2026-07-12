package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.velocitypowered.api.proxy.ServerConnection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolMessageSenderIntegrationTest {

    private static final long SENT_AT =
            1_750_000_000_000L;

    @Test
    void sendsEnvelopeEncodedByRealProtocolCodec() {
        ServerConnection target =
                mock(ServerConnection.class);

        when(target.sendPluginMessage(
                eq(ProtocolChannel.IDENTIFIER),
                any(byte[].class)
        )).thenReturn(true);

        ProtocolEnvelope<PingPayload> original =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PING,
                        new PingPayload(SENT_AT)
                );

        ProtocolMessageSender sender =
                new ProtocolMessageSender();

        assertTrue(sender.send(target, original));

        ArgumentCaptor<byte[]> encodedCaptor =
                ArgumentCaptor.forClass(byte[].class);

        verify(target).sendPluginMessage(
                eq(ProtocolChannel.IDENTIFIER),
                encodedCaptor.capture()
        );

        ProtocolEnvelope<?> decoded =
                new ProtocolJsonCodec().decodeRegistered(
                        encodedCaptor.getValue()
                );

        assertEquals(original, decoded);
    }
}
package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.velocitypowered.api.proxy.ServerConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolMessageSenderTest {

    private static final ProtocolEnvelope<PingPayload> ENVELOPE =
            ProtocolEnvelope.create(
                    ProtocolMessageType.PING,
                    new PingPayload(1_750_000_000_000L)
            );

    private final ProtocolJsonCodec codec =
            mock(ProtocolJsonCodec.class);
    private final ServerConnection target =
            mock(ServerConnection.class);

    private final ProtocolMessageSender sender =
            new ProtocolMessageSender(codec);

    @Test
    void sendsEncodedEnvelopeThroughProtocolChannel() {
        byte[] encoded = new byte[]{1, 2, 3};

        when(codec.encode(ENVELOPE))
                .thenReturn(encoded);
        when(target.sendPluginMessage(
                ProtocolChannel.IDENTIFIER,
                encoded
        )).thenReturn(true);

        assertTrue(sender.send(target, ENVELOPE));

        verify(target).sendPluginMessage(
                ProtocolChannel.IDENTIFIER,
                encoded
        );
    }

    @Test
    void returnsFalseWhenVelocityRejectsSending() {
        byte[] encoded = new byte[]{1, 2, 3};

        when(codec.encode(ENVELOPE))
                .thenReturn(encoded);
        when(target.sendPluginMessage(
                ProtocolChannel.IDENTIFIER,
                encoded
        )).thenReturn(false);

        assertFalse(sender.send(target, ENVELOPE));
    }

    @Test
    void rejectsNullTarget() {
        assertThrows(
                NullPointerException.class,
                () -> sender.send(null, ENVELOPE)
        );
    }

    @Test
    void rejectsNullEnvelope() {
        assertThrows(
                NullPointerException.class,
                () -> sender.send(target, null)
        );
    }

    @Test
    void rejectsNullCodec() {
        assertThrows(
                NullPointerException.class,
                () -> new ProtocolMessageSender(null)
        );
    }
}
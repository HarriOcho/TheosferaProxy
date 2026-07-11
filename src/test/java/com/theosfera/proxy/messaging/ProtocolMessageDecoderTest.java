package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolCodecException;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolMessageDecoderTest {

    private static final long SENT_AT =
            1_750_000_000_000L;

    private final ProtocolMessageDecoder decoder =
            new ProtocolMessageDecoder();

    @Test
    void decodesRegisteredProtocolMessage() {
        String json = """
                {
                  "version": 1,
                  "type": "PING",
                  "requestId": "417e98b4-74a1-467e-b453-a15be3af8996",
                  "timestamp": 1750000000000,
                  "payload": {
                    "sentAt": 1750000000000
                  }
                }
                """;

        ProtocolEnvelope<?> envelope = decoder.decode(
                json.getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(
                ProtocolMessageType.PING,
                envelope.type()
        );

        PingPayload payload = assertInstanceOf(
                PingPayload.class,
                envelope.payload()
        );

        assertEquals(SENT_AT, payload.sentAt());
    }

    @Test
    void rejectsUnknownProtocolMessage() {
        String json = """
                {
                  "version": 1,
                  "type": "UNKNOWN_MESSAGE",
                  "requestId": "417e98b4-74a1-467e-b453-a15be3af8996",
                  "timestamp": 1750000000000,
                  "payload": {}
                }
                """;

        assertThrows(
                ProtocolCodecException.class,
                () -> decoder.decode(
                        json.getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    @Test
    void rejectsNullCodec() {
        assertThrows(
                NullPointerException.class,
                () -> new ProtocolMessageDecoder(null)
        );
    }
}
package com.theosfera.proxy.control;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.transport.ProtocolFrameCodec;
import com.theosfera.proxy.backend.BackendIdentity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendControlMessageSenderTest {

    private final ProtocolJsonCodec jsonCodec =
            new ProtocolJsonCodec();
    private final ProtocolFrameCodec frameCodec =
            new ProtocolFrameCodec();
    private final BackendControlSessionRegistry sessionRegistry =
            new BackendControlSessionRegistry();
    private final BackendControlMessageSender sender =
            new BackendControlMessageSender(
                    jsonCodec,
                    frameCodec,
                    sessionRegistry
            );

    @Test
    void writesFramedEnvelopeForCurrentSession() throws Exception {
        BackendControlSession session = register("lobby-1");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ProtocolEnvelope<PingPayload> ping = pingEnvelope();

        assertTrue(sender.bind(session, output));
        assertTrue(sender.send(session, ping));

        byte[] frame = frameCodec.readFrame(
                new ByteArrayInputStream(output.toByteArray())
        ).orElseThrow();
        ProtocolEnvelope<?> decoded = jsonCodec.decodeRegistered(frame);

        assertEquals(ProtocolMessageType.PING, decoded.type());
        assertEquals(ping.requestId(), decoded.requestId());
        assertEquals(ping.payload(), decoded.payload());
        assertEquals(1, sender.boundSessionCount());
    }

    @Test
    void refusesToBindSessionThatIsNoLongerCurrent() {
        BackendControlSession first = register("lobby-1");
        BackendControlSession second = register("lobby-1");

        assertFalse(
                sender.bind(
                        first,
                        new ByteArrayOutputStream()
                )
        );
        assertTrue(
                sender.bind(
                        second,
                        new ByteArrayOutputStream()
                )
        );
    }

    @Test
    void replacementGenerationFencesOldWriter() throws Exception {
        BackendControlSession first = register("lobby-1");
        ByteArrayOutputStream firstOutput =
                new ByteArrayOutputStream();
        assertTrue(sender.bind(first, firstOutput));

        BackendControlSession second = register("lobby-1");
        ByteArrayOutputStream secondOutput =
                new ByteArrayOutputStream();
        assertTrue(sender.bind(second, secondOutput));

        assertFalse(sender.send(first, pingEnvelope()));
        assertFalse(sender.unbindIfCurrent(first));
        assertTrue(sender.send(second, pingEnvelope()));

        assertEquals(0, firstOutput.size());
        assertTrue(secondOutput.size() > 0);
        assertEquals(second, sender.findSession("lobby-1").orElseThrow());
    }

    @Test
    void failedWriteDropsOnlyBoundOutput() {
        BackendControlSession session = register("lobby-1");
        ByteArrayOutputStream failingOutput =
                new ByteArrayOutputStream() {
                    @Override
                    public synchronized void write(
                            byte[] bytes,
                            int offset,
                            int length
                    ) {
                        throw new IllegalStateException("boom");
                    }
                };

        assertTrue(sender.bind(session, failingOutput));

        try {
            sender.send(session, pingEnvelope());
        } catch (IOException exception) {
            throw new AssertionError(
                    "ByteArrayOutputStream does not throw IOException here",
                    exception
            );
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }

        assertEquals(1, sender.boundSessionCount());
        assertEquals(session, sender.findSession("lobby-1").orElseThrow());
    }

    private BackendControlSession register(String backendName) {
        return sessionRegistry.register(
                UUID.randomUUID(),
                new BackendIdentity(
                        backendName,
                        BackendType.LOBBY
                )
        ).current();
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

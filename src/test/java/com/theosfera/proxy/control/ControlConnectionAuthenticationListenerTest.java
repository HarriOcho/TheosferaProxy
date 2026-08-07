package com.theosfera.proxy.control;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.ControlAuthResponsePayload;
import com.theosfera.protocol.security.ControlAuthProof;
import com.theosfera.protocol.transport.ProtocolFrameCodec;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlConnectionAuthenticationListenerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_800_000_000_000L),
            ZoneOffset.UTC
    );
    private static final UUID CONNECTION_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );
    private static final UUID CHALLENGE_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );
    private static final UUID RESPONSE_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
    );
    private static final byte[] SECRET = createSecret();
    private static final byte[] NONCE = createNonce();

    private final ProtocolJsonCodec jsonCodec = new ProtocolJsonCodec();
    private final ProtocolFrameCodec frameCodec = new ProtocolFrameCodec();

    @Test
    void notifiesOnlyAfterAcceptedResultIsWritten() throws Exception {
        AtomicReference<BackendIdentity> notified = new AtomicReference<>();
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();

        ControlConnectionHandshakeHandler handler = newHandler(
                registry,
                notified::set
        );

        handler.authenticate(
                CONNECTION_ID,
                new ByteArrayInputStream(validResponseFrame()),
                new ByteArrayOutputStream()
        ).orElseThrow();

        assertEquals(
                new BackendIdentity("lobby-1", BackendType.LOBBY),
                notified.get()
        );
    }

    @Test
    void doesNotNotifyWhenAcceptedResultWriteFails() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();

        ControlConnectionHandshakeHandler handler = newHandler(
                registry,
                identity -> notifications.incrementAndGet()
        );

        assertThrows(
                IOException.class,
                () -> handler.authenticate(
                        CONNECTION_ID,
                        new ByteArrayInputStream(validResponseFrame()),
                        new FailAfterFirstFlushOutputStream()
                )
        );

        assertEquals(0, notifications.get());
        assertEquals(0, registry.size());
    }

    @Test
    void doesNotNotifyRejectedAuthentication() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();

        ControlConnectionHandshakeHandler handler = newHandler(
                registry,
                identity -> notifications.incrementAndGet()
        );

        handler.authenticate(
                CONNECTION_ID,
                new ByteArrayInputStream(
                        responseFrame("A".repeat(43))
                ),
                new ByteArrayOutputStream()
        );

        assertEquals(0, notifications.get());
        assertEquals(0, registry.size());
    }

    private ControlConnectionHandshakeHandler newHandler(
            BackendControlSessionRegistry registry,
            Consumer<BackendIdentity> listener
    ) {
        return new ControlConnectionHandshakeHandler(
                CLOCK,
                jsonCodec,
                frameCodec,
                newAuthenticationService(),
                registry,
                listener
        );
    }

    private ControlAuthenticationService newAuthenticationService() {
        BackendAuthorizationPolicy policy =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "lobby-1",
                                new BackendPolicyEntry(
                                        BackendType.LOBBY,
                                        100,
                                        90
                                )
                        )
                );

        return new ControlAuthenticationService(
                CLOCK,
                Duration.ofSeconds(10),
                policy,
                backendName -> "lobby-1".equals(backendName)
                        ? Optional.of(SECRET.clone())
                        : Optional.empty(),
                () -> CHALLENGE_ID,
                () -> NONCE.clone()
        );
    }

    private byte[] validResponseFrame() throws IOException {
        return responseFrame(
                ControlAuthProof.create(
                        SECRET,
                        ProtocolVersion.CURRENT,
                        CHALLENGE_ID,
                        nonceBase64Url(),
                        "lobby-1",
                        BackendType.LOBBY
                )
        );
    }

    private byte[] responseFrame(String proof) throws IOException {
        ProtocolEnvelope<ControlAuthResponsePayload> response =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.CONTROL_AUTH_RESPONSE,
                        RESPONSE_ID,
                        CLOCK.millis(),
                        new ControlAuthResponsePayload(
                                CHALLENGE_ID,
                                "lobby-1",
                                BackendType.LOBBY,
                                proof
                        )
                );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        frameCodec.writeFrame(output, jsonCodec.encode(response));
        return output.toByteArray();
    }

    private String nonceBase64Url() {
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(NONCE);
    }

    private static byte[] createSecret() {
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) {
            secret[index] = (byte) (index + 1);
        }
        return secret;
    }

    private static byte[] createNonce() {
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 7);
        return nonce;
    }

    private static final class FailAfterFirstFlushOutputStream
            extends OutputStream {

        private final ByteArrayOutputStream delegate =
                new ByteArrayOutputStream();
        private boolean failWrites;

        @Override
        public void write(int value) throws IOException {
            ensureWritable();
            delegate.write(value);
        }

        @Override
        public void write(
                byte[] value,
                int offset,
                int length
        ) throws IOException {
            ensureWritable();
            delegate.write(value, offset, length);
        }

        @Override
        public void flush() {
            failWrites = true;
        }

        private void ensureWritable() throws IOException {
            if (failWrites) {
                throw new IOException("simulated write failure");
            }
        }
    }
}

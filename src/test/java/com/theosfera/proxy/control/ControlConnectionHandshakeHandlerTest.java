package com.theosfera.proxy.control;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.ControlAuthResponsePayload;
import com.theosfera.protocol.message.payload.ControlAuthResultPayload;
import com.theosfera.protocol.message.payload.PingPayload;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConnectionHandshakeHandlerTest {

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
    void authenticatesAndRegistersBackendControlSession()
            throws Exception {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        ControlConnectionHandshakeHandler handler =
                newHandler(registry);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Optional<BackendControlSessionRegistration> result =
                handler.authenticate(
                        CONNECTION_ID,
                        new ByteArrayInputStream(validResponseFrame()),
                        output
                );

        BackendControlSessionRegistration registration =
                result.orElseThrow();
        assertEquals(
                "lobby-1",
                registration.current().identity().serverName()
        );
        assertEquals(
                BackendType.LOBBY,
                registration.current().identity().backendType()
        );
        assertTrue(registry.isCurrent(registration.current()));

        ByteArrayInputStream written =
                new ByteArrayInputStream(output.toByteArray());
        ProtocolEnvelope<?> challenge = readEnvelope(written);
        ProtocolEnvelope<?> authResult = readEnvelope(written);

        assertEquals(
                ProtocolMessageType.CONTROL_AUTH_CHALLENGE,
                challenge.type()
        );
        assertEquals(CHALLENGE_ID, challenge.requestId());
        assertEquals(
                ProtocolMessageType.CONTROL_AUTH_RESULT,
                authResult.type()
        );
        assertEquals(RESPONSE_ID, authResult.requestId());
        assertTrue(
                ((ControlAuthResultPayload) authResult.payload())
                        .accepted()
        );
        assertTrue(frameCodec.readFrame(written).isEmpty());
    }

    @Test
    void invalidProofIsRejectedWithoutRegisteringSession()
            throws Exception {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        ControlConnectionHandshakeHandler handler =
                newHandler(registry);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Optional<BackendControlSessionRegistration> result =
                handler.authenticate(
                        CONNECTION_ID,
                        new ByteArrayInputStream(
                                responseFrame("A".repeat(43))
                        ),
                        output
                );

        assertTrue(result.isEmpty());
        assertEquals(0, registry.size());

        ByteArrayInputStream written =
                new ByteArrayInputStream(output.toByteArray());
        readEnvelope(written);
        ProtocolEnvelope<?> authResult = readEnvelope(written);
        ControlAuthResultPayload payload =
                (ControlAuthResultPayload) authResult.payload();

        assertFalse(payload.accepted());
        assertEquals(
                "Backend control authentication rejected",
                payload.message()
        );
    }

    @Test
    void eofAfterChallengeCancelsPendingAuthentication()
            throws Exception {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        ControlAuthenticationService authenticationService =
                newAuthenticationService();
        ControlConnectionHandshakeHandler handler =
                new ControlConnectionHandshakeHandler(
                        CLOCK,
                        jsonCodec,
                        frameCodec,
                        authenticationService,
                        registry
                );

        Optional<BackendControlSessionRegistration> result =
                handler.authenticate(
                        CONNECTION_ID,
                        new ByteArrayInputStream(new byte[0]),
                        new ByteArrayOutputStream()
                );

        assertTrue(result.isEmpty());
        assertEquals(0, authenticationService.pendingCount());
        assertEquals(0, registry.size());
    }

    @Test
    void unexpectedMessageTypeFailsClosedAndCancelsChallenge()
            throws Exception {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        ControlAuthenticationService authenticationService =
                newAuthenticationService();
        ControlConnectionHandshakeHandler handler =
                new ControlConnectionHandshakeHandler(
                        CLOCK,
                        jsonCodec,
                        frameCodec,
                        authenticationService,
                        registry
                );

        ProtocolEnvelope<PingPayload> ping = new ProtocolEnvelope<>(
                ProtocolVersion.CURRENT,
                ProtocolMessageType.PING,
                RESPONSE_ID,
                CLOCK.millis(),
                new PingPayload(CLOCK.millis())
        );

        assertThrows(
                ControlConnectionProtocolException.class,
                () -> handler.authenticate(
                        CONNECTION_ID,
                        new ByteArrayInputStream(frame(ping)),
                        new ByteArrayOutputStream()
                )
        );

        assertEquals(0, authenticationService.pendingCount());
        assertEquals(0, registry.size());
    }

    @Test
    void resultWriteFailureRollsBackToPreviousOwner()
            throws Exception {
        BackendControlSessionRegistry registry =
                new BackendControlSessionRegistry();
        BackendControlSession previous = registry.register(
                UUID.fromString(
                        "44444444-4444-4444-4444-444444444444"
                ),
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        ).current();
        ControlConnectionHandshakeHandler handler =
                newHandler(registry);

        assertThrows(
                IOException.class,
                () -> handler.authenticate(
                        CONNECTION_ID,
                        new ByteArrayInputStream(validResponseFrame()),
                        new FailAfterFirstFlushOutputStream()
                )
        );

        assertTrue(registry.isCurrent(previous));
        assertEquals(
                previous,
                registry.find("lobby-1").orElseThrow()
        );
    }

    private ControlConnectionHandshakeHandler newHandler(
            BackendControlSessionRegistry registry
    ) {
        return new ControlConnectionHandshakeHandler(
                CLOCK,
                jsonCodec,
                frameCodec,
                newAuthenticationService(),
                registry
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
        return frame(response);
    }

    private byte[] frame(ProtocolEnvelope<?> envelope)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        frameCodec.writeFrame(
                output,
                jsonCodec.encode(envelope)
        );
        return output.toByteArray();
    }

    private ProtocolEnvelope<?> readEnvelope(
            ByteArrayInputStream input
    ) throws IOException {
        byte[] frame = frameCodec.readFrame(input).orElseThrow();
        return jsonCodec.decodeRegistered(frame);
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

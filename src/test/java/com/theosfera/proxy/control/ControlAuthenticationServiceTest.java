package com.theosfera.proxy.control;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.ControlAuthChallengePayload;
import com.theosfera.protocol.message.payload.ControlAuthResponsePayload;
import com.theosfera.protocol.security.ControlAuthProof;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlAuthenticationServiceTest {

    private static final long START_MILLIS = 1_700_000_000_000L;
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(5);
    private static final UUID CONNECTION_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );
    private static final UUID CHALLENGE_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );
    private static final UUID RESPONSE_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
    );
    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] NONCE = createNonce();

    private MutableClock clock;
    private ControlAuthenticationService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START_MILLIS);

        BackendAuthorizationPolicy authorizationPolicy =
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

        BackendControlSecretProvider secretProvider = backendName ->
                "lobby-1".equals(backendName)
                        ? Optional.of(SECRET.clone())
                        : Optional.empty();

        service = new ControlAuthenticationService(
                clock,
                AUTH_TIMEOUT,
                authorizationPolicy,
                secretProvider,
                () -> CHALLENGE_ID,
                () -> NONCE.clone()
        );
    }

    @Test
    void authenticatesValidBackendProof() {
        ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                service.begin(CONNECTION_ID);

        ControlAuthenticationResult result = service.authenticate(
                CONNECTION_ID,
                response(
                        challenge,
                        challenge.requestId(),
                        "lobby-1",
                        BackendType.LOBBY,
                        SECRET
                )
        );

        assertTrue(result.accepted());
        assertEquals(
                ControlAuthenticationStatus.AUTHENTICATED,
                result.status()
        );
        assertEquals(
                "lobby-1",
                result.identityOptional()
                        .orElseThrow()
                        .serverName()
        );
        assertEquals(0, service.pendingCount());
    }

    @Test
    void consumesChallengeSoProofCannotBeReplayed() {
        ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                service.begin(CONNECTION_ID);
        ProtocolEnvelope<ControlAuthResponsePayload> response =
                response(
                        challenge,
                        challenge.requestId(),
                        "lobby-1",
                        BackendType.LOBBY,
                        SECRET
                );

        assertTrue(
                service.authenticate(CONNECTION_ID, response)
                        .accepted()
        );

        ControlAuthenticationResult replay =
                service.authenticate(CONNECTION_ID, response);

        assertFalse(replay.accepted());
        assertEquals(
                ControlAuthenticationStatus.NO_CHALLENGE,
                replay.status()
        );
    }

    @Test
    void rejectsInvalidProofAndConsumesChallenge() {
        ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                service.begin(CONNECTION_ID);
        byte[] wrongSecret =
                "fedcba9876543210fedcba9876543210"
                        .getBytes(StandardCharsets.UTF_8);

        ControlAuthenticationResult result = service.authenticate(
                CONNECTION_ID,
                response(
                        challenge,
                        challenge.requestId(),
                        "lobby-1",
                        BackendType.LOBBY,
                        wrongSecret
                )
        );

        assertEquals(
                ControlAuthenticationStatus.INVALID_PROOF,
                result.status()
        );
        assertEquals(0, service.pendingCount());
    }

    @Test
    void rejectsMismatchedChallengeRequestId() {
        ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                service.begin(CONNECTION_ID);
        UUID differentChallenge = UUID.fromString(
                "44444444-4444-4444-4444-444444444444"
        );

        ControlAuthenticationResult result = service.authenticate(
                CONNECTION_ID,
                response(
                        challenge,
                        differentChallenge,
                        "lobby-1",
                        BackendType.LOBBY,
                        SECRET
                )
        );

        assertEquals(
                ControlAuthenticationStatus.REQUEST_ID_MISMATCH,
                result.status()
        );
    }

    @Test
    void rejectsExpiredChallenge() {
        ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                service.begin(CONNECTION_ID);
        clock.advance(AUTH_TIMEOUT);

        ControlAuthenticationResult result = service.authenticate(
                CONNECTION_ID,
                response(
                        challenge,
                        challenge.requestId(),
                        "lobby-1",
                        BackendType.LOBBY,
                        SECRET
                )
        );

        assertEquals(
                ControlAuthenticationStatus.EXPIRED,
                result.status()
        );
    }

    @Test
    void rejectsUnknownBackendIdentity() {
        ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                service.begin(CONNECTION_ID);

        ControlAuthenticationResult result = service.authenticate(
                CONNECTION_ID,
                response(
                        challenge,
                        challenge.requestId(),
                        "unknown-1",
                        BackendType.LOBBY,
                        SECRET
                )
        );

        assertEquals(
                ControlAuthenticationStatus.IDENTITY_REJECTED,
                result.status()
        );
    }

    @Test
    void rejectsBackendTypeMismatch() {
        ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                service.begin(CONNECTION_ID);

        ControlAuthenticationResult result = service.authenticate(
                CONNECTION_ID,
                response(
                        challenge,
                        challenge.requestId(),
                        "lobby-1",
                        BackendType.SKYBLOCK,
                        SECRET
                )
        );

        assertEquals(
                ControlAuthenticationStatus.IDENTITY_REJECTED,
                result.status()
        );
    }

    @Test
    void rejectsBackendWithoutConfiguredSecret() {
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

        ControlAuthenticationService missingSecretService =
                new ControlAuthenticationService(
                        clock,
                        AUTH_TIMEOUT,
                        policy,
                        backendName -> Optional.empty(),
                        () -> CHALLENGE_ID,
                        () -> NONCE.clone()
                );

        ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                missingSecretService.begin(CONNECTION_ID);

        ControlAuthenticationResult result =
                missingSecretService.authenticate(
                        CONNECTION_ID,
                        response(
                                challenge,
                                challenge.requestId(),
                                "lobby-1",
                                BackendType.LOBBY,
                                SECRET
                        )
                );

        assertEquals(
                ControlAuthenticationStatus.SECRET_UNAVAILABLE,
                result.status()
        );
    }

    @Test
    void rejectsSecondPendingChallengeForSameConnection() {
        service.begin(CONNECTION_ID);

        assertThrows(
                IllegalStateException.class,
                () -> service.begin(CONNECTION_ID)
        );
    }

    @Test
    void cancelRemovesPendingChallenge() {
        service.begin(CONNECTION_ID);

        service.cancel(CONNECTION_ID);

        assertEquals(0, service.pendingCount());
    }

    private ProtocolEnvelope<ControlAuthResponsePayload> response(
            ProtocolEnvelope<ControlAuthChallengePayload> challenge,
            UUID challengeRequestId,
            String backendName,
            BackendType backendType,
            byte[] proofSecret
    ) {
        String proof = ControlAuthProof.create(
                proofSecret,
                ProtocolVersion.CURRENT,
                challenge.requestId(),
                challenge.payload().nonce(),
                backendName,
                backendType
        );

        return new ProtocolEnvelope<>(
                ProtocolVersion.CURRENT,
                ProtocolMessageType.CONTROL_AUTH_RESPONSE,
                RESPONSE_ID,
                clock.millis(),
                new ControlAuthResponsePayload(
                        challengeRequestId,
                        backendName,
                        backendType,
                        proof
                )
        );
    }

    private static byte[] createNonce() {
        byte[] nonce = new byte[
                ControlAuthChallengePayload.NONCE_BYTES
        ];

        for (int index = 0; index < nonce.length; index++) {
            nonce[index] = (byte) (index + 1);
        }

        return nonce;
    }

    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}

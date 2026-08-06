package com.theosfera.proxy.control;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendHelloPayload;
import com.theosfera.protocol.message.payload.ControlAuthChallengePayload;
import com.theosfera.protocol.message.payload.ControlAuthResponsePayload;
import com.theosfera.protocol.security.ControlAuthProof;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ControlAuthenticationService {

    private static final SecureRandom SECURE_RANDOM =
            new SecureRandom();

    private final Clock clock;
    private final Duration authenticationTimeout;
    private final BackendAuthorizationPolicy authorizationPolicy;
    private final BackendControlSecretProvider secretProvider;
    private final Supplier<UUID> requestIdGenerator;
    private final Supplier<byte[]> nonceGenerator;
    private final Map<UUID, PendingChallenge> pendingChallenges =
            new ConcurrentHashMap<>();

    public ControlAuthenticationService(
            Clock clock,
            Duration authenticationTimeout,
            BackendAuthorizationPolicy authorizationPolicy,
            BackendControlSecretProvider secretProvider
    ) {
        this(
                clock,
                authenticationTimeout,
                authorizationPolicy,
                secretProvider,
                UUID::randomUUID,
                ControlAuthenticationService::generateSecureNonce
        );
    }

    ControlAuthenticationService(
            Clock clock,
            Duration authenticationTimeout,
            BackendAuthorizationPolicy authorizationPolicy,
            BackendControlSecretProvider secretProvider,
            Supplier<UUID> requestIdGenerator,
            Supplier<byte[]> nonceGenerator
    ) {
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.authenticationTimeout = requirePositive(
                authenticationTimeout,
                "authenticationTimeout"
        );
        this.authorizationPolicy = Objects.requireNonNull(
                authorizationPolicy,
                "authorizationPolicy cannot be null"
        );
        this.secretProvider = Objects.requireNonNull(
                secretProvider,
                "secretProvider cannot be null"
        );
        this.requestIdGenerator = Objects.requireNonNull(
                requestIdGenerator,
                "requestIdGenerator cannot be null"
        );
        this.nonceGenerator = Objects.requireNonNull(
                nonceGenerator,
                "nonceGenerator cannot be null"
        );
    }

    public ProtocolEnvelope<ControlAuthChallengePayload> begin(
            UUID connectionId
    ) {
        UUID nonNullConnectionId = Objects.requireNonNull(
                connectionId,
                "connectionId cannot be null"
        );

        long issuedAt = clock.millis();
        if (issuedAt <= 0) {
            throw new IllegalStateException(
                    "clock must return a positive timestamp"
            );
        }

        UUID requestId = Objects.requireNonNull(
                requestIdGenerator.get(),
                "requestIdGenerator cannot return null"
        );

        byte[] nonceBytes = Objects.requireNonNull(
                nonceGenerator.get(),
                "nonceGenerator cannot return null"
        ).clone();

        if (nonceBytes.length != ControlAuthChallengePayload.NONCE_BYTES) {
            throw new IllegalStateException(
                    "nonceGenerator must return exactly "
                            + ControlAuthChallengePayload.NONCE_BYTES
                            + " bytes"
            );
        }

        String nonce = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(nonceBytes);

        long expiresAt;
        try {
            expiresAt = Math.addExact(
                    issuedAt,
                    authenticationTimeout.toMillis()
            );
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "authentication timeout overflow",
                    exception
            );
        }

        PendingChallenge pending = new PendingChallenge(
                requestId,
                nonce,
                ProtocolVersion.CURRENT,
                expiresAt
        );

        PendingChallenge previous = pendingChallenges.putIfAbsent(
                nonNullConnectionId,
                pending
        );

        if (previous != null) {
            throw new IllegalStateException(
                    "control authentication already pending for connection"
            );
        }

        return new ProtocolEnvelope<>(
                ProtocolVersion.CURRENT,
                ProtocolMessageType.CONTROL_AUTH_CHALLENGE,
                requestId,
                issuedAt,
                new ControlAuthChallengePayload(nonce)
        );
    }

    public ControlAuthenticationResult authenticate(
            UUID connectionId,
            ProtocolEnvelope<ControlAuthResponsePayload> envelope
    ) {
        UUID nonNullConnectionId = Objects.requireNonNull(
                connectionId,
                "connectionId cannot be null"
        );
        ProtocolEnvelope<ControlAuthResponsePayload> nonNullEnvelope =
                Objects.requireNonNull(
                        envelope,
                        "envelope cannot be null"
                );

        if (!ProtocolMessageType.CONTROL_AUTH_RESPONSE.equals(
                nonNullEnvelope.type()
        )) {
            throw new IllegalArgumentException(
                    "envelope must contain CONTROL_AUTH_RESPONSE"
            );
        }

        PendingChallenge pending = pendingChallenges.remove(
                nonNullConnectionId
        );

        if (pending == null) {
            return ControlAuthenticationResult.rejected(
                    ControlAuthenticationStatus.NO_CHALLENGE
            );
        }

        if (clock.millis() >= pending.expiresAt()) {
            return ControlAuthenticationResult.rejected(
                    ControlAuthenticationStatus.EXPIRED
            );
        }

        ControlAuthResponsePayload payload =
                nonNullEnvelope.payload();

        if (!pending.requestId().equals(
                payload.challengeRequestId()
        )) {
            return ControlAuthenticationResult.rejected(
                    ControlAuthenticationStatus.REQUEST_ID_MISMATCH
            );
        }

        Optional<BackendIdentity> authorizedIdentity =
                authorizationPolicy.authorize(
                        payload.backendName(),
                        new BackendHelloPayload(
                                payload.backendName(),
                                payload.backendType()
                        )
                );

        if (authorizedIdentity.isEmpty()) {
            return ControlAuthenticationResult.rejected(
                    ControlAuthenticationStatus.IDENTITY_REJECTED
            );
        }

        Optional<byte[]> secret = Objects.requireNonNull(
                secretProvider.findSecret(
                        payload.backendName()
                ),
                "secretProvider cannot return null"
        );

        if (secret.isEmpty()) {
            return ControlAuthenticationResult.rejected(
                    ControlAuthenticationStatus.SECRET_UNAVAILABLE
            );
        }

        byte[] secretBytes = secret.orElseThrow().clone();

        final boolean validProof;
        try {
            validProof = ControlAuthProof.verify(
                    secretBytes,
                    pending.protocolVersion(),
                    pending.requestId(),
                    pending.nonce(),
                    payload.backendName(),
                    payload.backendType(),
                    payload.proof()
            );
        } catch (IllegalArgumentException exception) {
            return ControlAuthenticationResult.rejected(
                    ControlAuthenticationStatus.SECRET_UNAVAILABLE
            );
        }

        if (!validProof) {
            return ControlAuthenticationResult.rejected(
                    ControlAuthenticationStatus.INVALID_PROOF
            );
        }

        return ControlAuthenticationResult.authenticated(
                authorizedIdentity.orElseThrow()
        );
    }

    public void cancel(UUID connectionId) {
        Objects.requireNonNull(
                connectionId,
                "connectionId cannot be null"
        );
        pendingChallenges.remove(connectionId);
    }

    public int pendingCount() {
        return pendingChallenges.size();
    }

    public void clear() {
        pendingChallenges.clear();
    }

    private static byte[] generateSecureNonce() {
        byte[] nonce = new byte[
                ControlAuthChallengePayload.NONCE_BYTES
        ];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        Duration nonNullValue = Objects.requireNonNull(
                value,
                name + " cannot be null"
        );

        if (nonNullValue.isZero()
                || nonNullValue.isNegative()
                || nonNullValue.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }

        return nonNullValue;
    }

    private record PendingChallenge(
            UUID requestId,
            String nonce,
            int protocolVersion,
            long expiresAt
    ) {
    }
}

package com.theosfera.proxy.control;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.ControlAuthChallengePayload;
import com.theosfera.protocol.message.payload.ControlAuthResponsePayload;
import com.theosfera.protocol.message.payload.ControlAuthResultPayload;
import com.theosfera.protocol.transport.ProtocolFrameCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ControlConnectionHandshakeHandler
        implements ControlConnectionAuthenticator {

    private static final String ACCEPTED_MESSAGE =
            "Backend control authentication accepted";
    private static final String REJECTED_MESSAGE =
            "Backend control authentication rejected";

    private final Clock clock;
    private final ProtocolJsonCodec jsonCodec;
    private final ProtocolFrameCodec frameCodec;
    private final ControlAuthenticationService authenticationService;
    private final BackendControlSessionRegistry sessionRegistry;

    public ControlConnectionHandshakeHandler(
            Clock clock,
            ProtocolJsonCodec jsonCodec,
            ProtocolFrameCodec frameCodec,
            ControlAuthenticationService authenticationService,
            BackendControlSessionRegistry sessionRegistry
    ) {
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.jsonCodec = Objects.requireNonNull(
                jsonCodec,
                "jsonCodec cannot be null"
        );
        this.frameCodec = Objects.requireNonNull(
                frameCodec,
                "frameCodec cannot be null"
        );
        this.authenticationService = Objects.requireNonNull(
                authenticationService,
                "authenticationService cannot be null"
        );
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
    }

    @Override
    public Optional<BackendControlSessionRegistration> authenticate(
            UUID connectionId,
            InputStream input,
            OutputStream output
    ) throws IOException {
        UUID nonNullConnectionId = Objects.requireNonNull(
                connectionId,
                "connectionId cannot be null"
        );
        InputStream nonNullInput = Objects.requireNonNull(
                input,
                "input cannot be null"
        );
        OutputStream nonNullOutput = Objects.requireNonNull(
                output,
                "output cannot be null"
        );

        boolean challengePending = false;

        try {
            ProtocolEnvelope<ControlAuthChallengePayload> challenge =
                    authenticationService.begin(nonNullConnectionId);
            challengePending = true;
            writeEnvelope(nonNullOutput, challenge);

            Optional<byte[]> responseFrame =
                    frameCodec.readFrame(nonNullInput);

            if (responseFrame.isEmpty()) {
                authenticationService.cancel(nonNullConnectionId);
                return Optional.empty();
            }

            ProtocolEnvelope<ControlAuthResponsePayload> response =
                    decodeResponse(responseFrame.orElseThrow());

            ControlAuthenticationResult authenticationResult =
                    authenticationService.authenticate(
                            nonNullConnectionId,
                            response
                    );
            challengePending = false;

            if (!authenticationResult.accepted()) {
                writeResult(
                        nonNullOutput,
                        response.requestId(),
                        false
                );
                return Optional.empty();
            }

            BackendControlSessionRegistration registration =
                    sessionRegistry.register(
                            nonNullConnectionId,
                            authenticationResult
                                    .identityOptional()
                                    .orElseThrow()
                    );

            try {
                writeResult(
                        nonNullOutput,
                        response.requestId(),
                        true
                );
            } catch (IOException | RuntimeException exception) {
                sessionRegistry.rollback(registration);
                throw exception;
            }

            return Optional.of(registration);
        } catch (IOException | RuntimeException exception) {
            if (challengePending) {
                authenticationService.cancel(nonNullConnectionId);
            }
            throw exception;
        }
    }

    private ProtocolEnvelope<ControlAuthResponsePayload> decodeResponse(
            byte[] frame
    ) throws ControlConnectionProtocolException {
        final ProtocolEnvelope<?> decoded;

        try {
            decoded = jsonCodec.decodeRegistered(frame);
        } catch (RuntimeException exception) {
            throw new ControlConnectionProtocolException(
                    "Invalid control authentication response frame",
                    exception
            );
        }

        if (!ProtocolMessageType.CONTROL_AUTH_RESPONSE.equals(
                decoded.type()
        )) {
            throw new ControlConnectionProtocolException(
                    "Expected CONTROL_AUTH_RESPONSE"
            );
        }

        if (!(decoded.payload()
                instanceof ControlAuthResponsePayload payload)) {
            throw new ControlConnectionProtocolException(
                    "CONTROL_AUTH_RESPONSE payload type mismatch"
            );
        }

        return new ProtocolEnvelope<>(
                decoded.version(),
                decoded.type(),
                decoded.requestId(),
                decoded.timestamp(),
                payload
        );
    }

    private void writeResult(
            OutputStream output,
            UUID requestId,
            boolean accepted
    ) throws IOException {
        long timestamp = clock.millis();
        if (timestamp <= 0) {
            throw new IllegalStateException(
                    "clock must return a positive timestamp"
            );
        }

        ProtocolEnvelope<ControlAuthResultPayload> result =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.CONTROL_AUTH_RESULT,
                        requestId,
                        timestamp,
                        new ControlAuthResultPayload(
                                accepted,
                                accepted
                                        ? ACCEPTED_MESSAGE
                                        : REJECTED_MESSAGE
                        )
                );

        writeEnvelope(output, result);
    }

    private void writeEnvelope(
            OutputStream output,
            ProtocolEnvelope<?> envelope
    ) throws IOException {
        frameCodec.writeFrame(
                output,
                jsonCodec.encode(envelope)
        );
    }
}

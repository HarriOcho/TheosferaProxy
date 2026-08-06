package com.theosfera.proxy.control;

import com.theosfera.protocol.codec.ProtocolCodecException;
import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PongPayload;
import com.theosfera.protocol.transport.ProtocolFrameCodec;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;

public final class BackendControlPongHandler
        implements AuthenticatedControlConnectionHandler {

    private final ProtocolJsonCodec jsonCodec;
    private final ProtocolFrameCodec frameCodec;
    private final BackendControlSessionRegistry sessionRegistry;
    private final PendingBackendPingRegistry pendingPingRegistry;
    private final BackendHealthRegistry healthRegistry;
    private final Logger logger;

    public BackendControlPongHandler(
            ProtocolJsonCodec jsonCodec,
            ProtocolFrameCodec frameCodec,
            BackendControlSessionRegistry sessionRegistry,
            PendingBackendPingRegistry pendingPingRegistry,
            BackendHealthRegistry healthRegistry,
            Logger logger
    ) {
        this.jsonCodec = Objects.requireNonNull(
                jsonCodec,
                "jsonCodec cannot be null"
        );
        this.frameCodec = Objects.requireNonNull(
                frameCodec,
                "frameCodec cannot be null"
        );
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
        this.pendingPingRegistry = Objects.requireNonNull(
                pendingPingRegistry,
                "pendingPingRegistry cannot be null"
        );
        this.healthRegistry = Objects.requireNonNull(
                healthRegistry,
                "healthRegistry cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    @Override
    public void handle(
            BackendControlSession session,
            InputStream input,
            OutputStream output
    ) throws IOException {
        BackendControlSession nonNullSession = Objects.requireNonNull(
                session,
                "session cannot be null"
        );
        InputStream nonNullInput = Objects.requireNonNull(
                input,
                "input cannot be null"
        );
        Objects.requireNonNull(
                output,
                "output cannot be null"
        );

        while (sessionRegistry.isCurrent(nonNullSession)) {
            Optional<byte[]> frame = frameCodec.readFrame(nonNullInput);

            if (frame.isEmpty()) {
                return;
            }

            if (!sessionRegistry.isCurrent(nonNullSession)) {
                return;
            }

            ProtocolEnvelope<?> envelope = decode(frame.orElseThrow());

            if (!ProtocolMessageType.PONG.equals(envelope.type())) {
                throw new ControlConnectionProtocolException(
                        "Only PONG is allowed after backend control authentication"
                );
            }

            if (!(envelope.payload() instanceof PongPayload pongPayload)) {
                throw new ControlConnectionProtocolException(
                        "PONG control envelope requires PongPayload"
                );
            }

            String backendName =
                    nonNullSession.identity().serverName();

            boolean matched = pendingPingRegistry.consumeMatching(
                    backendName,
                    envelope.requestId(),
                    pongPayload.pingSentAt()
            );

            if (!matched) {
                logger.warn(
                        "PONG de control no correlacionado rechazado desde {} "
                                + "(generation {}, requestId: {}).",
                        backendName,
                        nonNullSession.generation(),
                        envelope.requestId()
                );
                continue;
            }

            if (!sessionRegistry.isCurrent(nonNullSession)) {
                return;
            }

            healthRegistry.markHealthy(backendName);
        }
    }

    private ProtocolEnvelope<?> decode(byte[] frame)
            throws ControlConnectionProtocolException {
        try {
            return jsonCodec.decodeRegistered(frame);
        } catch (ProtocolCodecException | IllegalArgumentException exception) {
            throw new ControlConnectionProtocolException(
                    "Malformed post-authentication control envelope",
                    exception
            );
        }
    }
}

package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PongPayload;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import org.slf4j.Logger;

import java.util.Objects;

public final class PongMessageHandler
        implements ProtocolMessageHandler {

    private final PendingBackendPingRegistry pendingPingRegistry;
    private final BackendHealthRegistry healthRegistry;
    private final Logger logger;

    public PongMessageHandler(
            PendingBackendPingRegistry pendingPingRegistry,
            BackendHealthRegistry healthRegistry,
            Logger logger
    ) {
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
    public String messageType() {
        return ProtocolMessageType.PONG;
    }

    @Override
    public void handle(ProtocolMessageContext context) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        PongPayload payload = requirePongPayload(
                context.envelope()
        );

        boolean matched = pendingPingRegistry.consumeMatching(
                context.serverName(),
                context.envelope().requestId(),
                payload.pingSentAt()
        );

        if (!matched) {
            logger.warn(
                    "PONG no correlacionado rechazado desde {} "
                            + "(requestId: {}).",
                    context.serverName(),
                    context.envelope().requestId()
            );
            return;
        }

        healthRegistry.markHealthy(context.serverName());
    }

    private PongPayload requirePongPayload(
            ProtocolEnvelope<?> envelope
    ) {
        if (!(envelope.payload()
                instanceof PongPayload pongPayload)) {
            throw new IllegalArgumentException(
                    "PONG envelope requires PongPayload"
            );
        }

        return pongPayload;
    }
}

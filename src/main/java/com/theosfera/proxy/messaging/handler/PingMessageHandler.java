package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PongPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Objects;

public final class PingMessageHandler
        implements ProtocolMessageHandler {

    private final ProtocolMessageSender sender;
    private final Logger logger;
    private final Clock clock;

    public PingMessageHandler(
            ProtocolMessageSender sender,
            Logger logger
    ) {
        this(
                sender,
                logger,
                Clock.systemUTC()
        );
    }

    PingMessageHandler(
            ProtocolMessageSender sender,
            Logger logger,
            Clock clock
    ) {
        this.sender = Objects.requireNonNull(
                sender,
                "sender cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
    }

    @Override
    public String messageType() {
        return ProtocolMessageType.PING;
    }

    @Override
    public void handle(ProtocolMessageContext context) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        PingPayload pingPayload = requirePingPayload(
                context.envelope()
        );

        long respondedAt = Math.max(
                clock.millis(),
                pingPayload.sentAt()
        );

        PongPayload pongPayload = new PongPayload(
                pingPayload.sentAt(),
                respondedAt
        );

        ProtocolEnvelope<PongPayload> response =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.PONG,
                        context.envelope().requestId(),
                        respondedAt,
                        pongPayload
                );

        if (!sender.send(context.source(), response)) {
            logger.warn(
                    "No se pudo enviar PONG al backend {} "
                            + "(requestId: {}).",
                    context.serverName(),
                    response.requestId()
            );
        }
    }

    private PingPayload requirePingPayload(
            ProtocolEnvelope<?> envelope
    ) {
        if (!(envelope.payload()
                instanceof PingPayload pingPayload)) {
            throw new IllegalArgumentException(
                    "PING envelope requires PingPayload"
            );
        }

        return pingPayload;
    }
}
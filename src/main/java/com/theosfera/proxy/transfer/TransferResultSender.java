package com.theosfera.proxy.transfer;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.TransferResultPayload;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class TransferResultSender {

    private final ProtocolMessageSender sender;
    private final Logger logger;
    private final Clock clock;

    public TransferResultSender(
            ProtocolMessageSender sender,
            Logger logger
    ) {
        this(
                sender,
                logger,
                Clock.systemUTC()
        );
    }

    TransferResultSender(
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

    public boolean send(
            ProtocolMessageContext context,
            UUID playerId,
            TransferResultStatus status,
            String message
    ) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        TransferResultPayload payload =
                new TransferResultPayload(
                        playerId,
                        status,
                        message
                );

        ProtocolEnvelope<TransferResultPayload> response =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.TRANSFER_RESULT,
                        context.envelope().requestId(),
                        clock.millis(),
                        payload
                );

        boolean sent = sender.send(
                context.source(),
                response
        );

        if (!sent) {
            logger.warn(
                    "No se pudo enviar TRANSFER_RESULT a {} "
                            + "(requestId: {}, playerId: {}, "
                            + "status: {}).",
                    context.serverName(),
                    response.requestId(),
                    playerId,
                    status
            );
        }

        return sent;
    }
}
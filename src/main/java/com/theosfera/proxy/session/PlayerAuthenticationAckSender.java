package com.theosfera.proxy.session;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedAckPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class PlayerAuthenticationAckSender {

    private final ProtocolMessageSender sender;
    private final Logger logger;
    private final Clock clock;

    public PlayerAuthenticationAckSender(
            ProtocolMessageSender sender,
            Logger logger
    ) {
        this(
                sender,
                logger,
                Clock.systemUTC()
        );
    }

    PlayerAuthenticationAckSender(
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
            boolean accepted,
            String message
    ) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        PlayerAuthenticatedAckPayload payload =
                new PlayerAuthenticatedAckPayload(
                        playerId,
                        accepted,
                        message
                );

        ProtocolEnvelope<PlayerAuthenticatedAckPayload> response =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType
                                .PLAYER_AUTHENTICATED_ACK,
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
                    "No se pudo enviar "
                            + "PLAYER_AUTHENTICATED_ACK a {} "
                            + "(requestId: {}, playerId: {}, "
                            + "accepted: {}).",
                    context.serverName(),
                    response.requestId(),
                    playerId,
                    accepted
            );
        }

        return sent;
    }
}

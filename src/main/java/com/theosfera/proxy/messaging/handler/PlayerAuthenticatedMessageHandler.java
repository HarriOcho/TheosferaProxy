package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerAuthenticationAckSender;
import com.theosfera.proxy.session.PlayerSessionRegistrationResult;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.Objects;

public final class PlayerAuthenticatedMessageHandler
        implements ProtocolMessageHandler {

    private final AuthenticatedPlayerSessionRegistry
            sessionRegistry;
    private final PlayerAuthenticationAckSender
            acknowledgementSender;
    private final Logger logger;

    public PlayerAuthenticatedMessageHandler(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerAuthenticationAckSender acknowledgementSender,
            Logger logger
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );

        this.acknowledgementSender =
                Objects.requireNonNull(
                        acknowledgementSender,
                        "acknowledgementSender cannot be null"
                );

        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    @Override
    public String messageType() {
        return ProtocolMessageType.PLAYER_AUTHENTICATED;
    }

    @Override
    public void handle(ProtocolMessageContext context) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        PlayerAuthenticatedPayload payload =
                requireAuthenticatedPayload(
                        context.envelope()
                );

        Player carrier = context.source().getPlayer();

        if (!carrier.getUniqueId().equals(
                payload.playerId()
        )) {
            rejectIdentityMismatch(
                    context,
                    payload,
                    carrier
            );
            return;
        }

        if (!carrier.getUsername().equals(
                payload.playerName()
        )) {
            rejectIdentityMismatch(
                    context,
                    payload,
                    carrier
            );
            return;
        }

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        payload.playerId(),
                        payload.playerName(),
                        payload.authenticatedAt()
                );

        PlayerSessionRegistrationResult result =
                sessionRegistry.register(session);

        switch (result) {
            case REGISTERED -> {
                acknowledgementSender.send(
                        context,
                        session.playerId(),
                        true,
                        "Player session registered"
                );

                logger.info(
                        "Sesión autenticada registrada para {} "
                                + "({}) desde {}.",
                        session.playerName(),
                        session.playerId(),
                        context.serverName()
                );
            }

            case ALREADY_REGISTERED -> {
                acknowledgementSender.send(
                        context,
                        session.playerId(),
                        true,
                        "Player session already registered"
                );

                logger.debug(
                        "Sesión autenticada ya registrada para {} "
                                + "({}).",
                        session.playerName(),
                        session.playerId()
                );
            }

            case CONFLICT -> {
                acknowledgementSender.send(
                        context,
                        session.playerId(),
                        false,
                        "Player session conflict"
                );

                logger.warn(
                        "Conflicto de sesión autenticada para {} "
                                + "recibido desde {}.",
                        session.playerId(),
                        context.serverName()
                );
            }
        }
    }

    private void rejectIdentityMismatch(
            ProtocolMessageContext context,
            PlayerAuthenticatedPayload payload,
            Player carrier
    ) {
        acknowledgementSender.send(
                context,
                payload.playerId(),
                false,
                "Player identity mismatch"
        );

        logger.warn(
                "PLAYER_AUTHENTICATED rechazado desde {}: "
                        + "la identidad declarada ({}, {}) "
                        + "no coincide con el jugador portador "
                        + "({}, {}).",
                context.serverName(),
                payload.playerId(),
                payload.playerName(),
                carrier.getUniqueId(),
                carrier.getUsername()
        );
    }

    private PlayerAuthenticatedPayload
    requireAuthenticatedPayload(
            ProtocolEnvelope<?> envelope
    ) {
        if (!(envelope.payload()
                instanceof PlayerAuthenticatedPayload payload)) {
            throw new IllegalArgumentException(
                    "PLAYER_AUTHENTICATED envelope requires "
                            + "PlayerAuthenticatedPayload"
            );
        }

        return payload;
    }
}

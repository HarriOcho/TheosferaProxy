package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PlayerServerReadyPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.session.PlayerPresenceUpdateResult;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import org.slf4j.Logger;

import java.util.Objects;

public final class PlayerServerReadyMessageHandler
        implements ProtocolMessageHandler {

    private final PlayerServerPresenceRegistry
            presenceRegistry;
    private final Logger logger;

    public PlayerServerReadyMessageHandler(
            PlayerServerPresenceRegistry presenceRegistry,
            Logger logger
    ) {
        this.presenceRegistry = Objects.requireNonNull(
                presenceRegistry,
                "presenceRegistry cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    @Override
    public String messageType() {
        return ProtocolMessageType.PLAYER_SERVER_READY;
    }

    @Override
    public void handle(ProtocolMessageContext context) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        PlayerServerReadyPayload payload =
                requireReadyPayload(context.envelope());

        if (!context.serverName().equals(
                payload.backendName()
        )) {
            logger.warn(
                    "PLAYER_SERVER_READY rechazado desde {}: "
                            + "el payload declaró otro backend.",
                    context.serverName()
            );
            return;
        }

        PlayerServerPresence presence =
                new PlayerServerPresence(
                        payload.playerId(),
                        payload.backendName(),
                        payload.readyAt()
                );

        PlayerPresenceUpdateResult result =
                presenceRegistry.update(presence);

        switch (result) {
            case RECORDED, UPDATED -> logger.info(
                    "Jugador {} listo en {}.",
                    presence.playerId(),
                    presence.backendName()
            );

            case ALREADY_RECORDED -> logger.debug(
                    "Presencia ya registrada para {} en {}.",
                    presence.playerId(),
                    presence.backendName()
            );

            case NOT_AUTHENTICATED -> logger.warn(
                    "PLAYER_SERVER_READY rechazado para {}: "
                            + "el jugador no está autenticado.",
                    presence.playerId()
            );

            case STALE -> logger.debug(
                    "PLAYER_SERVER_READY atrasado ignorado "
                            + "para {} desde {}.",
                    presence.playerId(),
                    presence.backendName()
            );

            case CONFLICT -> logger.warn(
                    "Conflicto de presencia para {} desde {}.",
                    presence.playerId(),
                    presence.backendName()
            );
        }
    }

    private PlayerServerReadyPayload requireReadyPayload(
            ProtocolEnvelope<?> envelope
    ) {
        if (!(envelope.payload()
                instanceof PlayerServerReadyPayload payload)) {
            throw new IllegalArgumentException(
                    "PLAYER_SERVER_READY envelope requires "
                            + "PlayerServerReadyPayload"
            );
        }

        return payload;
    }
}
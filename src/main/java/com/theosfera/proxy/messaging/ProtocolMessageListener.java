package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolCodecException;
import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.util.Objects;

public final class ProtocolMessageListener {

    private final Logger logger;
    private final ProtocolMessageDecoder decoder;
    private final BackendMessageAuthorizer authorizer;
    private final ProtocolMessageDispatcher dispatcher;

    public ProtocolMessageListener(
            Logger logger,
            BackendMessageAuthorizer authorizer,
            ProtocolMessageDispatcher dispatcher
    ) {
        this(
                logger,
                new ProtocolMessageDecoder(),
                authorizer,
                dispatcher
        );
    }

    ProtocolMessageListener(
            Logger logger,
            ProtocolMessageDecoder decoder,
            BackendMessageAuthorizer authorizer,
            ProtocolMessageDispatcher dispatcher
    ) {
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
        this.decoder = Objects.requireNonNull(
                decoder,
                "decoder cannot be null"
        );
        this.authorizer = Objects.requireNonNull(
                authorizer,
                "authorizer cannot be null"
        );
        this.dispatcher = Objects.requireNonNull(
                dispatcher,
                "dispatcher cannot be null"
        );
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!ProtocolChannel.IDENTIFIER.equals(
                event.getIdentifier()
        )) {
            return;
        }

        event.setResult(
                PluginMessageEvent.ForwardResult.handled()
        );

        if (!(event.getSource()
                instanceof ServerConnection serverConnection)) {
            logger.warn(
                    "Mensaje de protocolo rechazado: "
                            + "el origen no es una conexión backend."
            );
            return;
        }

        byte[] data = event.getData();
        String serverName =
                serverConnection.getServerInfo().getName();

        if (data.length == 0) {
            logger.warn(
                    "Mensaje de protocolo vacío rechazado desde {}.",
                    serverName
            );
            return;
        }

        if (data.length > ProtocolJsonCodec.MAX_MESSAGE_BYTES) {
            logger.warn(
                    "Mensaje de protocolo sobredimensionado "
                            + "rechazado desde {}: {} bytes.",
                    serverName,
                    data.length
            );
            return;
        }

        final ProtocolEnvelope<?> envelope;

        try {
            envelope = decoder.decode(data);
        } catch (ProtocolCodecException exception) {
            logger.warn(
                    "Mensaje de protocolo inválido rechazado "
                            + "desde {}.",
                    serverName
            );
            return;
        }

        ProtocolMessageContext context =
                new ProtocolMessageContext(
                        serverConnection,
                        envelope
                );

        if (!authorizer.isAuthorized(
                context.serverName(),
                envelope.type()
        )) {
            logger.warn(
                    "Mensaje de protocolo {} no autorizado "
                            + "rechazado desde {}.",
                    envelope.type(),
                    context.serverName()
            );
            return;
        }

        if (!dispatcher.dispatch(context)) {
            logger.debug(
                    "Mensaje de protocolo {} sin handler "
                            + "recibido desde {} "
                            + "(requestId: {}).",
                    envelope.type(),
                    context.serverName(),
                    envelope.requestId()
            );
        }
    }
}
package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.util.Objects;

public final class ProtocolMessageListener {

    private final Logger logger;

    public ProtocolMessageListener(Logger logger) {
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
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

        if (data.length == 0) {
            logger.warn(
                    "Mensaje de protocolo vacío rechazado desde {}.",
                    serverConnection.getServerInfo().getName()
            );
            return;
        }

        if (data.length > ProtocolJsonCodec.MAX_MESSAGE_BYTES) {
            logger.warn(
                    "Mensaje de protocolo sobredimensionado "
                            + "rechazado desde {}: {} bytes.",
                    serverConnection.getServerInfo().getName(),
                    data.length
            );
            return;
        }

        logger.debug(
                "Mensaje de protocolo recibido desde {}: {} bytes.",
                serverConnection.getServerInfo().getName(),
                data.length
        );
    }
}
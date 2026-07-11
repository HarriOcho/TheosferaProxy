package com.theosfera.proxy.messaging;

import com.theosfera.protocol.message.ProtocolMessageRegistry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ProtocolMessageDispatcher {

    private final Map<String, ProtocolMessageHandler> handlers;

    public ProtocolMessageDispatcher(
            Collection<ProtocolMessageHandler> handlers
    ) {
        Objects.requireNonNull(
                handlers,
                "handlers cannot be null"
        );

        Map<String, ProtocolMessageHandler> registered =
                new LinkedHashMap<>();

        for (ProtocolMessageHandler handler : handlers) {
            ProtocolMessageHandler nonNullHandler =
                    Objects.requireNonNull(
                            handler,
                            "handler cannot be null"
                    );

            String messageType = Objects.requireNonNull(
                    nonNullHandler.messageType(),
                    "handler messageType cannot be null"
            );

            if (!ProtocolMessageRegistry.isRegistered(
                    messageType
            )) {
                throw new IllegalArgumentException(
                        "Handler uses an unregistered message type: "
                                + messageType
                );
            }

            ProtocolMessageHandler previous =
                    registered.putIfAbsent(
                            messageType,
                            nonNullHandler
                    );

            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate handler for message type: "
                                + messageType
                );
            }
        }

        this.handlers = Map.copyOf(registered);
    }

    public boolean dispatch(ProtocolMessageContext context) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        ProtocolMessageHandler handler = handlers.get(
                context.envelope().type()
        );

        if (handler == null) {
            return false;
        }

        handler.handle(context);
        return true;
    }

    public Set<String> registeredHandlerTypes() {
        return handlers.keySet();
    }
}
package com.theosfera.proxy.messaging;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.velocitypowered.api.proxy.ServerConnection;

import java.util.Objects;

public record ProtocolMessageContext(
        ServerConnection source,
        ProtocolEnvelope<?> envelope
) {

    public ProtocolMessageContext {
        Objects.requireNonNull(
                source,
                "source cannot be null"
        );
        Objects.requireNonNull(
                envelope,
                "envelope cannot be null"
        );
    }

    public String serverName() {
        return source.getServerInfo().getName();
    }
}
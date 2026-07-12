package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.velocitypowered.api.proxy.ServerConnection;

import java.util.Objects;

public final class ProtocolMessageSender {

    private final ProtocolJsonCodec codec;

    public ProtocolMessageSender() {
        this(new ProtocolJsonCodec());
    }

    ProtocolMessageSender(ProtocolJsonCodec codec) {
        this.codec = Objects.requireNonNull(
                codec,
                "codec cannot be null"
        );
    }

    public boolean send(
            ServerConnection target,
            ProtocolEnvelope<?> envelope
    ) {
        Objects.requireNonNull(
                target,
                "target cannot be null"
        );
        Objects.requireNonNull(
                envelope,
                "envelope cannot be null"
        );

        byte[] encoded = codec.encode(envelope);

        return target.sendPluginMessage(
                ProtocolChannel.IDENTIFIER,
                encoded
        );
    }
}
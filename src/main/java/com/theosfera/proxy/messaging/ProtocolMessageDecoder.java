package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;

import java.util.Objects;

public final class ProtocolMessageDecoder {

    private final ProtocolJsonCodec codec;

    public ProtocolMessageDecoder() {
        this(new ProtocolJsonCodec());
    }

    ProtocolMessageDecoder(ProtocolJsonCodec codec) {
        this.codec = Objects.requireNonNull(
                codec,
                "codec cannot be null"
        );
    }

    public ProtocolEnvelope<?> decode(byte[] data) {
        return codec.decodeRegistered(data);
    }
}
package com.theosfera.proxy.messaging;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

public final class ProtocolChannel {

    public static final String NAMESPACE = "theosfera";
    public static final String NAME = "network";

    public static final MinecraftChannelIdentifier IDENTIFIER =
            MinecraftChannelIdentifier.create(
                    NAMESPACE,
                    NAME
            );

    private ProtocolChannel() {
        throw new UnsupportedOperationException(
                "ProtocolChannel cannot be instantiated"
        );
    }
}
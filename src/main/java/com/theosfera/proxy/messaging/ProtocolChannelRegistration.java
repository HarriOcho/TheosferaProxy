package com.theosfera.proxy.messaging;

import com.velocitypowered.api.proxy.messages.ChannelRegistrar;

import java.util.Objects;

public final class ProtocolChannelRegistration {

    private final ChannelRegistrar channelRegistrar;
    private boolean registered;

    public ProtocolChannelRegistration(
            ChannelRegistrar channelRegistrar
    ) {
        this.channelRegistrar = Objects.requireNonNull(
                channelRegistrar,
                "channelRegistrar cannot be null"
        );
    }

    public void register() {
        if (registered) {
            return;
        }

        channelRegistrar.register(
                ProtocolChannel.IDENTIFIER
        );
        registered = true;
    }

    public void unregister() {
        if (!registered) {
            return;
        }

        channelRegistrar.unregister(
                ProtocolChannel.IDENTIFIER
        );
        registered = false;
    }

    public boolean isRegistered() {
        return registered;
    }
}
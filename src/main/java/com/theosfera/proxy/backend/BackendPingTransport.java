package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.ProtocolEnvelope;

import java.io.IOException;

@FunctionalInterface
public interface BackendPingTransport {

    boolean send(
            String backendName,
            ProtocolEnvelope<?> envelope
    ) throws IOException;
}

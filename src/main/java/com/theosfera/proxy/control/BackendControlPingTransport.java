package com.theosfera.proxy.control;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.proxy.backend.BackendPingTransport;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public final class BackendControlPingTransport
        implements BackendPingTransport {

    private final BackendControlMessageSender messageSender;

    public BackendControlPingTransport(
            BackendControlMessageSender messageSender
    ) {
        this.messageSender = Objects.requireNonNull(
                messageSender,
                "messageSender cannot be null"
        );
    }

    @Override
    public boolean send(
            String backendName,
            ProtocolEnvelope<?> envelope
    ) throws IOException {
        String normalizedBackendName = requireBackendName(backendName);
        ProtocolEnvelope<?> nonNullEnvelope = Objects.requireNonNull(
                envelope,
                "envelope cannot be null"
        );

        Optional<BackendControlSession> session =
                messageSender.findSession(normalizedBackendName);

        if (session.isEmpty()) {
            return false;
        }

        return messageSender.send(
                session.orElseThrow(),
                nonNullEnvelope
        );
    }

    private static String requireBackendName(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
        }

        return normalized;
    }
}

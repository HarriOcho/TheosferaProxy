package com.theosfera.proxy.backend;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class BackendPingEmitter {

    private final Clock clock;
    private final Supplier<UUID> requestIdGenerator;
    private final PendingBackendPingRegistry pendingPingRegistry;
    private final BackendPingTransport transport;
    private final Logger logger;

    public BackendPingEmitter(
            Clock clock,
            Supplier<UUID> requestIdGenerator,
            PendingBackendPingRegistry pendingPingRegistry,
            BackendPingTransport transport,
            Logger logger
    ) {
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.requestIdGenerator = Objects.requireNonNull(
                requestIdGenerator,
                "requestIdGenerator cannot be null"
        );
        this.pendingPingRegistry = Objects.requireNonNull(
                pendingPingRegistry,
                "pendingPingRegistry cannot be null"
        );
        this.transport = Objects.requireNonNull(
                transport,
                "transport cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    /**
     * Temporary compatibility constructor while production wiring is moved
     * from Plugin Messaging to the authenticated control transport.
     */
    public BackendPingEmitter(
            Clock clock,
            Supplier<UUID> requestIdGenerator,
            PendingBackendPingRegistry pendingPingRegistry,
            BackendPingConnectionResolver connectionResolver,
            ProtocolMessageSender sender,
            Logger logger
    ) {
        this(
                clock,
                requestIdGenerator,
                pendingPingRegistry,
                legacyTransport(connectionResolver, sender),
                logger
        );
    }

    public boolean emit(String serverName) {
        String normalizedServerName = requireServerName(serverName);
        long sentAt = clock.millis();
        UUID requestId = requestIdGenerator.get();

        PendingBackendPing pendingPing =
                new PendingBackendPing(
                        normalizedServerName,
                        requestId,
                        sentAt
                );

        BackendPingRegistrationResult registrationResult =
                pendingPingRegistry.registerIfAbsentOrExpired(
                        pendingPing
                );

        if (registrationResult
                == BackendPingRegistrationResult.ALREADY_PENDING) {
            logger.debug(
                    "No se envio PING a {}: ya existe un desafio "
                            + "vigente.",
                    normalizedServerName
            );
            return false;
        }

        ProtocolEnvelope<PingPayload> envelope =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.PING,
                        requestId,
                        sentAt,
                        new PingPayload(sentAt)
                );

        try {
            if (transport.send(normalizedServerName, envelope)) {
                return true;
            }
        } catch (IOException | RuntimeException exception) {
            pendingPingRegistry.removeIfMatches(pendingPing);
            logger.warn(
                    "Error al enviar PING a {} (requestId: {}).",
                    normalizedServerName,
                    requestId,
                    exception
            );
            return false;
        }

        pendingPingRegistry.removeIfMatches(pendingPing);
        logger.debug(
                "No se envio PING a {}: el transporte de health "
                        + "no tiene una sesion disponible "
                        + "(requestId: {}).",
                normalizedServerName,
                requestId
        );
        return false;
    }

    private static BackendPingTransport legacyTransport(
            BackendPingConnectionResolver connectionResolver,
            ProtocolMessageSender sender
    ) {
        BackendPingConnectionResolver nonNullResolver =
                Objects.requireNonNull(
                        connectionResolver,
                        "connectionResolver cannot be null"
                );
        ProtocolMessageSender nonNullSender = Objects.requireNonNull(
                sender,
                "sender cannot be null"
        );

        return (backendName, envelope) ->
                nonNullResolver.resolve(backendName)
                        .map(connection ->
                                nonNullSender.send(
                                        connection,
                                        envelope
                                )
                        )
                        .orElse(false);
    }

    private static String requireServerName(String serverName) {
        String normalized = Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        ).trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "serverName cannot be blank"
            );
        }

        return normalized;
    }
}

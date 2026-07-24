package com.theosfera.proxy.backend;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class BackendPingEmitter {

    private final Clock clock;
    private final Supplier<UUID> requestIdGenerator;
    private final PendingBackendPingRegistry pendingPingRegistry;
    private final BackendPingConnectionResolver connectionResolver;
    private final ProtocolMessageSender sender;
    private final Logger logger;

    public BackendPingEmitter(
            Clock clock,
            Supplier<UUID> requestIdGenerator,
            PendingBackendPingRegistry pendingPingRegistry,
            BackendPingConnectionResolver connectionResolver,
            ProtocolMessageSender sender,
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
        this.connectionResolver = Objects.requireNonNull(
                connectionResolver,
                "connectionResolver cannot be null"
        );
        this.sender = Objects.requireNonNull(
                sender,
                "sender cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public boolean emit(String serverName) {
        String normalizedServerName = requireServerName(serverName);

        Optional<ServerConnection> connection =
                connectionResolver.resolve(normalizedServerName);

        if (connection.isEmpty()) {
            logger.debug(
                    "No se envio PING a {}: no hay conexion "
                            + "backend disponible.",
                    normalizedServerName
            );
            return false;
        }

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
            if (sender.send(connection.orElseThrow(), envelope)) {
                return true;
            }
        } catch (RuntimeException exception) {
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
        logger.warn(
                "Velocity rechazo el envio de PING a {} "
                        + "(requestId: {}).",
                normalizedServerName,
                requestId
        );
        return false;
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

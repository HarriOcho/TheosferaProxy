package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendHelloAckPayload;
import com.theosfera.protocol.message.payload.BackendHelloPayload;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendRegistrationResult;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public final class BackendHelloMessageHandler
        implements ProtocolMessageHandler {

    private final BackendAuthorizationPolicy authorizationPolicy;
    private final BackendIdentityRegistry identityRegistry;
    private final BackendBootstrapRegistry bootstrapRegistry;
    private final ProtocolMessageSender sender;
    private final Logger logger;
    private final Clock clock;

    public BackendHelloMessageHandler(
            BackendAuthorizationPolicy authorizationPolicy,
            BackendIdentityRegistry identityRegistry,
            BackendBootstrapRegistry bootstrapRegistry,
            ProtocolMessageSender sender,
            Logger logger
    ) {
        this(
                authorizationPolicy,
                identityRegistry,
                bootstrapRegistry,
                sender,
                logger,
                Clock.systemUTC()
        );
    }

    BackendHelloMessageHandler(
            BackendAuthorizationPolicy authorizationPolicy,
            BackendIdentityRegistry identityRegistry,
            BackendBootstrapRegistry bootstrapRegistry,
            ProtocolMessageSender sender,
            Logger logger,
            Clock clock
    ) {
        this.authorizationPolicy = Objects.requireNonNull(
                authorizationPolicy,
                "authorizationPolicy cannot be null"
        );

        this.identityRegistry = Objects.requireNonNull(
                identityRegistry,
                "identityRegistry cannot be null"
        );

        this.bootstrapRegistry = Objects.requireNonNull(
                bootstrapRegistry,
                "bootstrapRegistry cannot be null"
        );

        this.sender = Objects.requireNonNull(
                sender,
                "sender cannot be null"
        );

        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );

        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
    }

    @Override
    public String messageType() {
        return ProtocolMessageType.BACKEND_HELLO;
    }

    @Override
    public void handle(ProtocolMessageContext context) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        BackendHelloPayload helloPayload =
                requireHelloPayload(
                        context.envelope()
                );

        Optional<BackendIdentity> authorizedIdentity =
                authorizationPolicy.authorize(
                        context.serverName(),
                        helloPayload
                );

        if (authorizedIdentity.isEmpty()) {
            sendAcknowledgement(
                    context,
                    false,
                    "Backend authorization rejected"
            );

            logger.warn(
                    "Handshake de backend rechazado desde {}.",
                    context.serverName()
            );
            return;
        }

        BackendRegistrationResult registrationResult =
                identityRegistry.register(
                        authorizedIdentity.orElseThrow()
                );

        switch (registrationResult) {
            case REGISTERED -> {
                bootstrapRegistry.removeByTarget(
                        context.serverName()
                );

                sendAcknowledgement(
                        context,
                        true,
                        "Backend registered"
                );

                logger.info(
                        "Backend registrado: {} ({})",
                        context.serverName(),
                        helloPayload.backendType()
                );
            }
            case ALREADY_REGISTERED -> {
                bootstrapRegistry.removeByTarget(
                        context.serverName()
                );

                sendAcknowledgement(
                        context,
                        true,
                        "Backend already registered"
                );
            }
            case CONFLICT -> {
                sendAcknowledgement(
                        context,
                        false,
                        "Backend identity conflict"
                );

                logger.warn(
                        "Handshake conflictivo rechazado desde {}.",
                        context.serverName()
                );
            }
        }
    }

    private void sendAcknowledgement(
            ProtocolMessageContext context,
            boolean accepted,
            String message
    ) {
        long timestamp = clock.millis();

        BackendHelloAckPayload payload =
                new BackendHelloAckPayload(
                        accepted,
                        message
                );

        ProtocolEnvelope<BackendHelloAckPayload> response =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.BACKEND_HELLO_ACK,
                        context.envelope().requestId(),
                        timestamp,
                        payload
                );

        if (!sender.send(context.source(), response)) {
            logger.warn(
                    "No se pudo enviar BACKEND_HELLO_ACK a {} "
                            + "(requestId: {}).",
                    context.serverName(),
                    response.requestId()
            );
        }
    }

    private BackendHelloPayload requireHelloPayload(
            ProtocolEnvelope<?> envelope
    ) {
        if (!(envelope.payload()
                instanceof BackendHelloPayload helloPayload)) {
            throw new IllegalArgumentException(
                    "BACKEND_HELLO envelope requires "
                            + "BackendHelloPayload"
            );
        }

        return helloPayload;
    }
}

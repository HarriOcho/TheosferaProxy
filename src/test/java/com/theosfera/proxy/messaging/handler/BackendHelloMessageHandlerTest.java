package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendHelloAckPayload;
import com.theosfera.protocol.message.payload.BackendHelloPayload;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import com.theosfera.proxy.transfer.BackendBootstrapReservation;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendHelloMessageHandlerTest {

    private static final long RESPONSE_TIMESTAMP =
            1_750_000_000_000L;

    private final BackendBootstrapRegistry bootstrapRegistry =
            new BackendBootstrapRegistry();

    private final BackendAuthorizationPolicy policy =
            new BackendAuthorizationPolicy(
                    Map.of(
                            "auth-1",
                            BackendType.AUTH,
                            "lobby-1",
                            BackendType.LOBBY
                    )
            );

    private final BackendIdentityRegistry registry =
            new BackendIdentityRegistry();
    private final ProtocolMessageSender sender =
            mock(ProtocolMessageSender.class);
    private final Logger logger = mock(Logger.class);

    private final BackendHelloMessageHandler handler =
            new BackendHelloMessageHandler(
                    policy,
                    registry,
                    bootstrapRegistry,
                    sender,
                    logger,
                    Clock.fixed(
                            Instant.ofEpochMilli(
                                    RESPONSE_TIMESTAMP
                            ),
                            ZoneOffset.UTC
                    )
            );

    @Test
    void declaresBackendHelloMessageType() {
        assertEquals(
                ProtocolMessageType.BACKEND_HELLO,
                handler.messageType()
        );
    }

    @Test
    void registersAuthorizedBackendAndSendsAcceptedAck() {
        ServerConnection source =
                createServerConnection("lobby-1");

        ProtocolEnvelope<BackendHelloPayload> request =
                createHelloEnvelope(
                        "lobby-1",
                        BackendType.LOBBY
                );

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        BackendIdentity identity =
                registry.find("lobby-1").orElseThrow();

        assertEquals(
                BackendType.LOBBY,
                identity.backendType()
        );

        ProtocolEnvelope<?> response =
                captureResponse(source);

        assertEquals(
                ProtocolMessageType.BACKEND_HELLO_ACK,
                response.type()
        );
        assertEquals(
                request.requestId(),
                response.requestId()
        );
        assertEquals(
                RESPONSE_TIMESTAMP,
                response.timestamp()
        );

        BackendHelloAckPayload payload =
                (BackendHelloAckPayload) response.payload();

        assertTrue(payload.accepted());
        assertEquals(
                "Backend registered",
                payload.message()
        );

        verify(logger).info(
                "Backend registrado: {} ({})",
                "lobby-1",
                BackendType.LOBBY
        );
    }

    @Test
    void clearsBootstrapAfterAuthorizedHandshake() {
        UUID bootstrapRequestId =
                UUID.fromString(
                        "11111111-2222-3333-4444-555555555555"
                );

        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "lobby-1",
                        bootstrapRequestId,
                        UUID.fromString(
                                "417e98b4-74a1-467e-b453-a15be3af8996"
                        ),
                        RESPONSE_TIMESTAMP - 1_000L
                )
        );

        ServerConnection source =
                createServerConnection("lobby-1");

        ProtocolEnvelope<BackendHelloPayload> request =
                createHelloEnvelope(
                        "lobby-1",
                        BackendType.LOBBY
                );

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        assertTrue(
                bootstrapRegistry
                        .findByTarget("lobby-1")
                        .isEmpty()
        );

        assertTrue(
                bootstrapRegistry
                        .findByRequest(bootstrapRequestId)
                        .isEmpty()
        );
    }

    @Test
    void preservesBootstrapAfterRejectedHandshake() {
        UUID bootstrapRequestId =
                UUID.fromString(
                        "11111111-2222-3333-4444-555555555555"
                );

        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "auth-1",
                        bootstrapRequestId,
                        UUID.fromString(
                                "417e98b4-74a1-467e-b453-a15be3af8996"
                        ),
                        RESPONSE_TIMESTAMP - 1_000L
                )
        );

        ServerConnection source =
                createServerConnection("auth-1");

        ProtocolEnvelope<BackendHelloPayload> request =
                createHelloEnvelope(
                        "auth-1",
                        BackendType.LOBBY
                );

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        assertTrue(
                bootstrapRegistry
                        .findByTarget("auth-1")
                        .isPresent()
        );

        assertTrue(
                bootstrapRegistry
                        .findByRequest(bootstrapRequestId)
                        .isPresent()
        );
    }

    @Test
    void rejectsUnauthorizedBackendAndSendsRejectedAck() {
        ServerConnection source =
                createServerConnection("auth-1");

        ProtocolEnvelope<BackendHelloPayload> request =
                createHelloEnvelope(
                        "auth-1",
                        BackendType.LOBBY
                );

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        assertFalse(registry.isRegistered("auth-1"));

        ProtocolEnvelope<?> response =
                captureResponse(source);

        BackendHelloAckPayload payload =
                (BackendHelloAckPayload) response.payload();

        assertFalse(payload.accepted());
        assertEquals(
                "Backend authorization rejected",
                payload.message()
        );
        assertEquals(
                request.requestId(),
                response.requestId()
        );

        verify(logger).warn(
                "Handshake de backend rechazado desde {}.",
                "auth-1"
        );
    }

    @Test
    void acceptsIdenticalRepeatedHandshake() {
        registry.register(
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        );

        ServerConnection source =
                createServerConnection("lobby-1");

        ProtocolEnvelope<BackendHelloPayload> request =
                createHelloEnvelope(
                        "lobby-1",
                        BackendType.LOBBY
                );

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        BackendHelloAckPayload payload =
                (BackendHelloAckPayload) captureResponse(
                        source
                ).payload();

        assertTrue(payload.accepted());
        assertEquals(
                "Backend already registered",
                payload.message()
        );
    }

    @Test
    void rejectsConflictingRegisteredIdentity() {
        registry.register(
                new BackendIdentity(
                        "lobby-1",
                        BackendType.AUTH
                )
        );

        ServerConnection source =
                createServerConnection("lobby-1");

        ProtocolEnvelope<BackendHelloPayload> request =
                createHelloEnvelope(
                        "lobby-1",
                        BackendType.LOBBY
                );

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        ProtocolEnvelope<?> response =
                captureResponse(source);

        BackendHelloAckPayload payload =
                (BackendHelloAckPayload) response.payload();

        assertFalse(payload.accepted());
        assertEquals(
                "Backend identity conflict",
                payload.message()
        );
        assertEquals(
                BackendType.AUTH,
                registry.find("lobby-1")
                        .orElseThrow()
                        .backendType()
        );

        verify(logger).warn(
                "Handshake conflictivo rechazado desde {}.",
                "lobby-1"
        );
    }

    @Test
    void logsWhenAcknowledgementCannotBeSent() {
        ServerConnection source =
                createServerConnection("lobby-1");

        ProtocolEnvelope<BackendHelloPayload> request =
                createHelloEnvelope(
                        "lobby-1",
                        BackendType.LOBBY
                );

        when(sender.send(
                eq(source),
                any(ProtocolEnvelope.class)
        )).thenReturn(false);

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        request
                )
        );

        verify(logger).warn(
                "No se pudo enviar BACKEND_HELLO_ACK a {} "
                        + "(requestId: {}).",
                "lobby-1",
                request.requestId()
        );
    }

    private ProtocolEnvelope<BackendHelloPayload>
    createHelloEnvelope(
            String backendName,
            BackendType backendType
    ) {
        return ProtocolEnvelope.create(
                ProtocolMessageType.BACKEND_HELLO,
                new BackendHelloPayload(
                        backendName,
                        backendType
                )
        );
    }

    private ProtocolEnvelope<?> captureResponse(
            ServerConnection source
    ) {
        ArgumentCaptor<ProtocolEnvelope<?>> captor =
                ArgumentCaptor.forClass(
                        ProtocolEnvelope.class
                );

        verify(sender).send(
                eq(source),
                captor.capture()
        );

        return captor.getValue();
    }

    private ServerConnection createServerConnection(
            String serverName
    ) {
        ServerConnection source =
                mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);
        when(source.getServerInfo()).thenReturn(serverInfo);

        return source;
    }
}

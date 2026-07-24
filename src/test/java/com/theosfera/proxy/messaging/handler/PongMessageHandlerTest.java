package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PongPayload;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.backend.PendingBackendPing;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PongMessageHandlerTest {

    private static final long SENT_AT =
            1_750_000_000_000L;
    private static final long RESPONDED_AT =
            SENT_AT + 25;

    private final Clock clock = Clock.fixed(
            Instant.ofEpochMilli(RESPONDED_AT),
            ZoneOffset.UTC
    );
    private final PendingBackendPingRegistry pendingRegistry =
            new PendingBackendPingRegistry(
                    clock,
                    Duration.ofSeconds(10)
            );
    private final BackendHealthRegistry healthRegistry =
            new BackendHealthRegistry(
                    clock,
                    Duration.ofSeconds(15)
            );
    private final Logger logger = mock(Logger.class);
    private final PongMessageHandler handler =
            new PongMessageHandler(
                    pendingRegistry,
                    healthRegistry,
                    logger
            );

    private ProtocolEnvelope<PongPayload> pong;

    @BeforeEach
    void setUp() {
        pong = ProtocolEnvelope.create(
                ProtocolMessageType.PONG,
                new PongPayload(SENT_AT, RESPONDED_AT)
        );
    }

    @Test
    void marksBackendHealthyForCorrelatedPong() {
        pendingRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        pong.requestId(),
                        SENT_AT
                )
        );

        handler.handle(context("lobby-1", pong));

        assertEquals(
                BackendHealthStatus.HEALTHY,
                healthRegistry.status("lobby-1")
        );
    }

    @Test
    void rejectsPongFromDifferentBackendWithoutConsumingChallenge() {
        pendingRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        pong.requestId(),
                        SENT_AT
                )
        );

        handler.handle(context("skyblock-1", pong));

        assertEquals(
                BackendHealthStatus.UNKNOWN,
                healthRegistry.status("skyblock-1")
        );

        handler.handle(context("lobby-1", pong));

        assertEquals(
                BackendHealthStatus.HEALTHY,
                healthRegistry.status("lobby-1")
        );
    }

    @Test
    void rejectsRepeatedPong() {
        pendingRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        pong.requestId(),
                        SENT_AT
                )
        );

        ProtocolMessageContext context =
                context("lobby-1", pong);

        handler.handle(context);
        healthRegistry.remove("lobby-1");
        handler.handle(context);

        assertEquals(
                BackendHealthStatus.UNKNOWN,
                healthRegistry.status("lobby-1")
        );
        verify(logger).warn(
                "PONG no correlacionado rechazado desde {} "
                        + "(requestId: {}).",
                "lobby-1",
                pong.requestId()
        );
    }

    @Test
    void rejectsIncorrectPayloadType() {
        ProtocolEnvelope<PingPayload> invalid =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PONG,
                        new PingPayload(SENT_AT)
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> handler.handle(
                        context("lobby-1", invalid)
                )
        );
    }

    private ProtocolMessageContext context(
            String serverName,
            ProtocolEnvelope<?> envelope
    ) {
        ServerConnection source = mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);
        when(source.getServerInfo()).thenReturn(serverInfo);

        return new ProtocolMessageContext(source, envelope);
    }
}

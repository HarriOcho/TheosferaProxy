package com.theosfera.proxy.control;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PongPayload;
import com.theosfera.protocol.transport.ProtocolFrameCodec;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendPingEmitter;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BackendControlHeartbeatFlowTest {

    private static final long PING_SENT_AT =
            1_750_000_000_000L;
    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    @Test
    void sendsPingAndConsumesCorrelatedPongOverControlSession()
            throws Exception {
        Clock clock = Clock.fixed(
                Instant.ofEpochMilli(PING_SENT_AT),
                ZoneOffset.UTC
        );
        ProtocolJsonCodec jsonCodec = new ProtocolJsonCodec();
        ProtocolFrameCodec frameCodec = new ProtocolFrameCodec();
        BackendControlSessionRegistry sessionRegistry =
                new BackendControlSessionRegistry();
        PendingBackendPingRegistry pendingRegistry =
                new PendingBackendPingRegistry(
                        clock,
                        Duration.ofSeconds(10)
                );
        BackendHealthRegistry healthRegistry =
                new BackendHealthRegistry(
                        clock,
                        Duration.ofSeconds(15)
                );
        BackendControlMessageSender messageSender =
                new BackendControlMessageSender(
                        jsonCodec,
                        frameCodec,
                        sessionRegistry
                );

        BackendControlSession session = sessionRegistry.register(
                UUID.randomUUID(),
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        ).current();

        ByteArrayOutputStream proxyToBackend =
                new ByteArrayOutputStream();
        assertTrue(messageSender.bind(session, proxyToBackend));

        BackendPingEmitter emitter = new BackendPingEmitter(
                clock,
                () -> REQUEST_ID,
                pendingRegistry,
                new BackendControlPingTransport(messageSender),
                mock(Logger.class)
        );

        assertTrue(emitter.emit("lobby-1"));

        byte[] pingFrame = frameCodec.readFrame(
                new ByteArrayInputStream(
                        proxyToBackend.toByteArray()
                )
        ).orElseThrow();
        ProtocolEnvelope<?> pingEnvelope =
                jsonCodec.decodeRegistered(pingFrame);

        assertEquals(
                ProtocolMessageType.PING,
                pingEnvelope.type()
        );
        assertEquals(REQUEST_ID, pingEnvelope.requestId());
        assertEquals(
                new PingPayload(PING_SENT_AT),
                pingEnvelope.payload()
        );

        long respondedAt = PING_SENT_AT + 25;
        ProtocolEnvelope<PongPayload> pong =
                new ProtocolEnvelope<>(
                        ProtocolVersion.CURRENT,
                        ProtocolMessageType.PONG,
                        REQUEST_ID,
                        respondedAt,
                        new PongPayload(
                                PING_SENT_AT,
                                respondedAt
                        )
                );

        ByteArrayOutputStream backendToProxy =
                new ByteArrayOutputStream();
        frameCodec.writeFrame(
                backendToProxy,
                jsonCodec.encode(pong)
        );

        new BackendControlPongHandler(
                jsonCodec,
                frameCodec,
                sessionRegistry,
                pendingRegistry,
                healthRegistry,
                mock(Logger.class)
        ).handle(
                session,
                new ByteArrayInputStream(
                        backendToProxy.toByteArray()
                ),
                new ByteArrayOutputStream()
        );

        assertEquals(
                BackendHealthStatus.HEALTHY,
                healthRegistry.status("lobby-1")
        );
        assertTrue(pendingRegistry.snapshot().isEmpty());
    }
}

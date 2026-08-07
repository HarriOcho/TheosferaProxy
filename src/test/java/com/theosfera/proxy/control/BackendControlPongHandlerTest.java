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
import com.theosfera.proxy.backend.PendingBackendPing;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BackendControlPongHandlerTest {

    private static final long PING_SENT_AT = 1_000L;
    private static final long RESPONDED_AT = 1_500L;
    private static final UUID REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );

    private ProtocolJsonCodec jsonCodec;
    private ProtocolFrameCodec frameCodec;
    private BackendControlSessionRegistry sessionRegistry;
    private PendingBackendPingRegistry pendingPingRegistry;
    private BackendHealthRegistry healthRegistry;
    private Logger logger;
    private BackendControlPongHandler handler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.ofEpochMilli(2_000L),
                ZoneOffset.UTC
        );
        jsonCodec = new ProtocolJsonCodec();
        frameCodec = new ProtocolFrameCodec();
        sessionRegistry = new BackendControlSessionRegistry();
        pendingPingRegistry = new PendingBackendPingRegistry(
                clock,
                Duration.ofSeconds(10)
        );
        healthRegistry = new BackendHealthRegistry(
                clock,
                Duration.ofSeconds(15)
        );
        logger = mock(Logger.class);
        handler = new BackendControlPongHandler(
                jsonCodec,
                frameCodec,
                sessionRegistry,
                pendingPingRegistry,
                healthRegistry,
                logger
        );
    }

    @Test
    void correlatedPongMarksCurrentBackendHealthy() throws Exception {
        BackendControlSession session = register("lobby-1");
        pendingPingRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        REQUEST_ID,
                        PING_SENT_AT
                )
        );

        handler.handle(
                session,
                inputFor(pong(REQUEST_ID, PING_SENT_AT)),
                new ByteArrayOutputStream()
        );

        assertTrue(pendingPingRegistry.snapshot().isEmpty());
        assertEquals(
                BackendHealthStatus.HEALTHY,
                healthRegistry.status("lobby-1")
        );
    }

    @Test
    void uncorrelatedPongDoesNotRefreshHealth() throws Exception {
        BackendControlSession session = register("lobby-1");
        pendingPingRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        REQUEST_ID,
                        PING_SENT_AT
                )
        );
        UUID wrongRequestId = UUID.fromString(
                "00000000-0000-0000-0000-000000000002"
        );

        handler.handle(
                session,
                inputFor(pong(wrongRequestId, PING_SENT_AT)),
                new ByteArrayOutputStream()
        );

        assertEquals(
                BackendHealthStatus.UNKNOWN,
                healthRegistry.status("lobby-1")
        );
        assertEquals(
                REQUEST_ID,
                pendingPingRegistry.snapshot()
                        .get("lobby-1")
                        .requestId()
        );
        verify(logger).warn(
                "PONG de control no correlacionado rechazado desde {} "
                        + "(generation {}, requestId: {}).",
                "lobby-1",
                session.generation(),
                wrongRequestId
        );
    }

    @Test
    void replacedGenerationCannotRefreshHealth() throws Exception {
        BackendControlSession first = register("lobby-1");
        register("lobby-1");
        pendingPingRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        REQUEST_ID,
                        PING_SENT_AT
                )
        );

        handler.handle(
                first,
                inputFor(pong(REQUEST_ID, PING_SENT_AT)),
                new ByteArrayOutputStream()
        );

        assertEquals(
                BackendHealthStatus.UNKNOWN,
                healthRegistry.status("lobby-1")
        );
        assertEquals(
                REQUEST_ID,
                pendingPingRegistry.snapshot()
                        .get("lobby-1")
                        .requestId()
        );
    }

    @Test
    void rejectsUnexpectedPostAuthenticationMessage() throws Exception {
        BackendControlSession session = register("lobby-1");
        ProtocolEnvelope<PingPayload> ping = new ProtocolEnvelope<>(
                ProtocolVersion.CURRENT,
                ProtocolMessageType.PING,
                REQUEST_ID,
                PING_SENT_AT,
                new PingPayload(PING_SENT_AT)
        );

        assertThrows(
                ControlConnectionProtocolException.class,
                () -> handler.handle(
                        session,
                        inputFor(ping),
                        new ByteArrayOutputStream()
                )
        );
    }

    @Test
    void rejectsMalformedPostAuthenticationEnvelope() throws Exception {
        BackendControlSession session = register("lobby-1");
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        frameCodec.writeFrame(
                framed,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        assertThrows(
                ControlConnectionProtocolException.class,
                () -> handler.handle(
                        session,
                        new ByteArrayInputStream(framed.toByteArray()),
                        new ByteArrayOutputStream()
                )
        );
    }

    private BackendControlSession register(String backendName) {
        return sessionRegistry.register(
                UUID.randomUUID(),
                new BackendIdentity(
                        backendName,
                        BackendType.LOBBY
                )
        ).current();
    }

    private ProtocolEnvelope<PongPayload> pong(
            UUID requestId,
            long pingSentAt
    ) {
        return new ProtocolEnvelope<>(
                ProtocolVersion.CURRENT,
                ProtocolMessageType.PONG,
                requestId,
                RESPONDED_AT,
                new PongPayload(
                        pingSentAt,
                        RESPONDED_AT
                )
        );
    }

    private ByteArrayInputStream inputFor(
            ProtocolEnvelope<?> envelope
    ) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        frameCodec.writeFrame(
                output,
                jsonCodec.encode(envelope)
        );
        return new ByteArrayInputStream(output.toByteArray());
    }
}

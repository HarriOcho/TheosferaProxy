package com.theosfera.proxy.backend;

import com.theosfera.protocol.ProtocolVersion;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendPingEmitterTest {

    private static final Instant INITIAL_TIME =
            Instant.parse("2026-07-24T06:00:00Z");
    private static final Duration TIMEOUT =
            Duration.ofSeconds(10);
    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private MutableClock clock;
    private PendingBackendPingRegistry pendingRegistry;
    private BackendPingTransport transport;
    private Logger logger;
    private BackendPingEmitter emitter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(INITIAL_TIME);
        pendingRegistry = new PendingBackendPingRegistry(
                clock,
                TIMEOUT
        );
        transport = mock(BackendPingTransport.class);
        logger = mock(Logger.class);
        emitter = new BackendPingEmitter(
                clock,
                () -> REQUEST_ID,
                pendingRegistry,
                transport,
                logger
        );
    }

    @Test
    void registersBeforeSending() throws Exception {
        PendingBackendPingRegistry registry =
                mock(PendingBackendPingRegistry.class);
        BackendPingEmitter orderedEmitter =
                new BackendPingEmitter(
                        clock,
                        () -> REQUEST_ID,
                        registry,
                        transport,
                        logger
                );

        when(registry.registerIfAbsentOrExpired(any()))
                .thenReturn(
                        BackendPingRegistrationResult.REGISTERED
                );
        when(transport.send(
                eq("lobby-1"),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        orderedEmitter.emit("lobby-1");

        InOrder inOrder = inOrder(registry, transport);
        inOrder.verify(registry)
                .registerIfAbsentOrExpired(any());
        inOrder.verify(transport)
                .send(
                        eq("lobby-1"),
                        any(ProtocolEnvelope.class)
                );
    }

    @Test
    void sendsDeterministicPingEnvelope() throws Exception {
        when(transport.send(
                eq("lobby-1"),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        assertTrue(emitter.emit("lobby-1"));

        ArgumentCaptor<ProtocolEnvelope<?>> envelopeCaptor =
                ArgumentCaptor.forClass(ProtocolEnvelope.class);

        verify(transport).send(
                eq("lobby-1"),
                envelopeCaptor.capture()
        );

        ProtocolEnvelope<?> envelope =
                envelopeCaptor.getValue();

        assertEquals(ProtocolVersion.CURRENT, envelope.version());
        assertEquals(ProtocolMessageType.PING, envelope.type());
        assertEquals(REQUEST_ID, envelope.requestId());
        assertEquals(clock.millis(), envelope.timestamp());

        PingPayload payload = (PingPayload) envelope.payload();

        assertEquals(clock.millis(), payload.sentAt());
        assertEquals(
                new PendingBackendPing(
                        "lobby-1",
                        REQUEST_ID,
                        clock.millis()
                ),
                pendingRegistry.snapshot().get("lobby-1")
        );
    }

    @Test
    void rollsBackChallengeWhenTransportIsUnavailable()
            throws Exception {
        when(transport.send(
                eq("lobby-1"),
                any(ProtocolEnvelope.class)
        )).thenReturn(false);

        assertFalse(emitter.emit("lobby-1"));
        assertTrue(pendingRegistry.snapshot().isEmpty());
    }

    @Test
    void doesNotSendWhenActiveChallengeExists()
            throws Exception {
        pendingRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        clock.millis()
                )
        );

        assertFalse(emitter.emit("lobby-1"));
        verify(transport, never()).send(
                any(),
                any(ProtocolEnvelope.class)
        );
    }

    @Test
    void replacesExpiredChallengeAndSendsNewPing()
            throws Exception {
        long oldSentAt = clock.millis();

        pendingRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        oldSentAt
                )
        );

        clock.advance(TIMEOUT.plusMillis(1));

        when(transport.send(
                eq("lobby-1"),
                any(ProtocolEnvelope.class)
        )).thenReturn(true);

        assertTrue(emitter.emit("lobby-1"));

        PendingBackendPing pendingPing =
                pendingRegistry.snapshot().get("lobby-1");

        assertEquals(REQUEST_ID, pendingPing.requestId());
        assertEquals(clock.millis(), pendingPing.sentAt());
    }

    @Test
    void rollsBackExactChallengeWhenTransportThrowsIo()
            throws Exception {
        IOException exception =
                new IOException("send failed");

        when(transport.send(
                eq("lobby-1"),
                any(ProtocolEnvelope.class)
        )).thenThrow(exception);

        assertFalse(emitter.emit("lobby-1"));
        assertTrue(pendingRegistry.snapshot().isEmpty());
        verify(logger).warn(
                "Error al enviar PING a {} (requestId: {}).",
                "lobby-1",
                REQUEST_ID,
                exception
        );
    }

    @Test
    void doesNotOwnVelocityConnectionOrHealthState() {
        for (java.lang.reflect.Field field
                : BackendPingEmitter.class.getDeclaredFields()) {
            assertFalse(
                    field.getType()
                            .equals(BackendPingConnectionResolver.class)
            );
            assertFalse(
                    field.getType()
                            .getName()
                            .equals(
                                    "com.theosfera.proxy.messaging.ProtocolMessageSender"
                            )
            );
            assertFalse(
                    field.getType()
                            .equals(BackendHealthRegistry.class)
            );
        }
    }

    private static final class MutableClock extends Clock {

        private Instant currentInstant;

        private MutableClock(Instant initialInstant) {
            this.currentInstant = initialInstant;
        }

        private void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}

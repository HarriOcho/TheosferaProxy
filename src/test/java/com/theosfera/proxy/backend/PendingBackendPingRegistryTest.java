package com.theosfera.proxy.backend;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingBackendPingRegistryTest {

    private static final long SENT_AT =
            Instant.parse("2026-07-24T06:00:00Z")
                    .toEpochMilli();
    private static final Duration TIMEOUT =
            Duration.ofSeconds(10);

    @Test
    void consumesExactChallengeOnlyOnce() {
        UUID requestId = UUID.randomUUID();
        PendingBackendPingRegistry registry =
                registryAt(SENT_AT + 1);

        registry.register(
                new PendingBackendPing(
                        "lobby-1",
                        requestId,
                        SENT_AT
                )
        );

        assertTrue(registry.consumeMatching(
                "lobby-1",
                requestId,
                SENT_AT
        ));
        assertFalse(registry.consumeMatching(
                "lobby-1",
                requestId,
                SENT_AT
        ));
    }

    @Test
    void preservesChallengeAfterWrongServerRequestOrTimestamp() {
        UUID requestId = UUID.randomUUID();
        PendingBackendPingRegistry registry =
                registryAt(SENT_AT + 1);

        registry.register(
                new PendingBackendPing(
                        "lobby-1",
                        requestId,
                        SENT_AT
                )
        );

        assertFalse(registry.consumeMatching(
                "skyblock-1",
                requestId,
                SENT_AT
        ));
        assertFalse(registry.consumeMatching(
                "lobby-1",
                UUID.randomUUID(),
                SENT_AT
        ));
        assertFalse(registry.consumeMatching(
                "lobby-1",
                requestId,
                SENT_AT + 1
        ));
        assertTrue(registry.consumeMatching(
                "lobby-1",
                requestId,
                SENT_AT
        ));
    }

    @Test
    void acceptsAtTimeoutBoundaryAndRejectsAfterIt() {
        UUID boundaryRequestId = UUID.randomUUID();
        PendingBackendPingRegistry boundaryRegistry =
                registryAt(SENT_AT + TIMEOUT.toMillis());

        boundaryRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        boundaryRequestId,
                        SENT_AT
                )
        );

        assertTrue(boundaryRegistry.consumeMatching(
                "lobby-1",
                boundaryRequestId,
                SENT_AT
        ));

        UUID expiredRequestId = UUID.randomUUID();
        PendingBackendPingRegistry expiredRegistry =
                registryAt(
                        SENT_AT + TIMEOUT.toMillis() + 1
                );

        expiredRegistry.register(
                new PendingBackendPing(
                        "lobby-1",
                        expiredRequestId,
                        SENT_AT
                )
        );

        assertFalse(expiredRegistry.consumeMatching(
                "lobby-1",
                expiredRequestId,
                SENT_AT
        ));
        assertTrue(expiredRegistry.snapshot().isEmpty());
    }

    private PendingBackendPingRegistry registryAt(long millis) {
        return new PendingBackendPingRegistry(
                Clock.fixed(
                        Instant.ofEpochMilli(millis),
                        ZoneOffset.UTC
                ),
                TIMEOUT
        );
    }
}

package com.theosfera.proxy.backend;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void registersWhenNoChallengeExists() {
        UUID requestId = UUID.randomUUID();
        PendingBackendPingRegistry registry =
                registryAt(SENT_AT + 1);

        PendingBackendPing pendingPing =
                new PendingBackendPing(
                        "lobby-1",
                        requestId,
                        SENT_AT
                );

        assertEquals(
                BackendPingRegistrationResult.REGISTERED,
                registry.registerIfAbsentOrExpired(pendingPing)
        );
        assertEquals(
                pendingPing,
                registry.snapshot().get("lobby-1")
        );
    }

    @Test
    void rejectsNewChallengeWhileExistingOneIsActive() {
        PendingBackendPingRegistry registry =
                registryAt(SENT_AT + 1);

        PendingBackendPing first =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT
                );
        PendingBackendPing second =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT + 1
                );

        assertEquals(
                BackendPingRegistrationResult.REGISTERED,
                registry.registerIfAbsentOrExpired(first)
        );
        assertEquals(
                BackendPingRegistrationResult.ALREADY_PENDING,
                registry.registerIfAbsentOrExpired(second)
        );
        assertEquals(
                first,
                registry.snapshot().get("lobby-1")
        );
    }

    @Test
    void replacesExpiredChallenge() {
        PendingBackendPingRegistry registry =
                registryAt(
                        SENT_AT + TIMEOUT.toMillis() + 1
                );

        PendingBackendPing expired =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT
                );
        PendingBackendPing replacement =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT + TIMEOUT.toMillis() + 1
                );

        registry.register(expired);

        assertEquals(
                BackendPingRegistrationResult.REGISTERED,
                registry.registerIfAbsentOrExpired(replacement)
        );
        assertEquals(
                replacement,
                registry.snapshot().get("lobby-1")
        );
    }

    @Test
    void rejectsReplacementAtTimeoutBoundaryAndAllowsAfterIt() {
        PendingBackendPing activeAtBoundary =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT
                );
        PendingBackendPing boundaryReplacement =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT + TIMEOUT.toMillis()
                );

        PendingBackendPingRegistry boundaryRegistry =
                registryAt(SENT_AT + TIMEOUT.toMillis());

        boundaryRegistry.register(activeAtBoundary);

        assertEquals(
                BackendPingRegistrationResult.ALREADY_PENDING,
                boundaryRegistry.registerIfAbsentOrExpired(
                        boundaryReplacement
                )
        );
        assertEquals(
                activeAtBoundary,
                boundaryRegistry.snapshot().get("lobby-1")
        );

        PendingBackendPing expired =
                new PendingBackendPing(
                        "lobby-1",
                        activeAtBoundary.requestId(),
                        SENT_AT
                );
        PendingBackendPing afterBoundaryReplacement =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT + TIMEOUT.toMillis() + 1
                );

        PendingBackendPingRegistry afterBoundaryRegistry =
                registryAt(
                        SENT_AT + TIMEOUT.toMillis() + 1
                );

        afterBoundaryRegistry.register(expired);

        assertEquals(
                BackendPingRegistrationResult.REGISTERED,
                afterBoundaryRegistry.registerIfAbsentOrExpired(
                        afterBoundaryReplacement
                )
        );
        assertEquals(
                afterBoundaryReplacement,
                afterBoundaryRegistry.snapshot().get("lobby-1")
        );
    }

    @Test
    void removeIfMatchesRemovesExactChallenge() {
        PendingBackendPingRegistry registry =
                registryAt(SENT_AT + 1);

        PendingBackendPing pendingPing =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT
                );

        registry.register(pendingPing);

        assertEquals(
                pendingPing,
                registry.removeIfMatches(pendingPing)
                        .orElseThrow()
        );
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void removeIfMatchesPreservesDifferentRequestId() {
        PendingBackendPingRegistry registry =
                registryAt(SENT_AT + 1);

        PendingBackendPing existing =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT
                );
        PendingBackendPing differentRequest =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT
                );

        registry.register(existing);

        assertTrue(
                registry.removeIfMatches(differentRequest).isEmpty()
        );
        assertEquals(
                existing,
                registry.snapshot().get("lobby-1")
        );
    }

    @Test
    void removeIfMatchesPreservesDifferentSentAt() {
        PendingBackendPingRegistry registry =
                registryAt(SENT_AT + 1);

        UUID requestId = UUID.randomUUID();
        PendingBackendPing existing =
                new PendingBackendPing(
                        "lobby-1",
                        requestId,
                        SENT_AT
                );
        PendingBackendPing differentSentAt =
                new PendingBackendPing(
                        "lobby-1",
                        requestId,
                        SENT_AT + 1
                );

        registry.register(existing);

        assertTrue(
                registry.removeIfMatches(differentSentAt).isEmpty()
        );
        assertEquals(
                existing,
                registry.snapshot().get("lobby-1")
        );
    }

    @Test
    void oldRollbackDoesNotRemoveNewerChallenge() {
        PendingBackendPingRegistry registry =
                registryAt(
                        SENT_AT + TIMEOUT.toMillis() + 1
                );

        PendingBackendPing oldChallenge =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT
                );
        PendingBackendPing newerChallenge =
                new PendingBackendPing(
                        "lobby-1",
                        UUID.randomUUID(),
                        SENT_AT + TIMEOUT.toMillis() + 1
                );

        registry.register(oldChallenge);
        registry.registerIfAbsentOrExpired(newerChallenge);

        assertTrue(
                registry.removeIfMatches(oldChallenge).isEmpty()
        );
        assertEquals(
                newerChallenge,
                registry.snapshot().get("lobby-1")
        );
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

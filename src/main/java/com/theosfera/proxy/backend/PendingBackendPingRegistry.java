package com.theosfera.proxy.backend;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PendingBackendPingRegistry {

    private final Clock clock;
    private final Duration responseTimeout;
    private final Map<String, PendingBackendPing> pendingByServer =
            new ConcurrentHashMap<>();

    public PendingBackendPingRegistry(
            Clock clock,
            Duration responseTimeout
    ) {
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.responseTimeout = requirePositiveTimeout(
                responseTimeout
        );
    }

    public void register(PendingBackendPing pendingPing) {
        PendingBackendPing challenge = Objects.requireNonNull(
                pendingPing,
                "pendingPing cannot be null"
        );

        pendingByServer.put(
                challenge.serverName(),
                challenge
        );
    }

    public boolean consumeMatching(
            String serverName,
            UUID requestId,
            long pingSentAt
    ) {
        String normalizedServerName =
                requireServerName(serverName);
        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );

        AtomicBoolean matched = new AtomicBoolean(false);

        pendingByServer.computeIfPresent(
                normalizedServerName,
                (ignored, pendingPing) -> {
                    if (isExpired(pendingPing)) {
                        return null;
                    }

                    if (pendingPing.requestId().equals(requestId)
                            && pendingPing.sentAt() == pingSentAt) {
                        matched.set(true);
                        return null;
                    }

                    return pendingPing;
                }
        );

        return matched.get();
    }

    public Map<String, PendingBackendPing> snapshot() {
        return Map.copyOf(pendingByServer);
    }

    public void remove(String serverName) {
        pendingByServer.remove(
                requireServerName(serverName)
        );
    }

    public void clear() {
        pendingByServer.clear();
    }

    private boolean isExpired(PendingBackendPing pendingPing) {
        long expiresAt = Math.addExact(
                pendingPing.sentAt(),
                responseTimeout.toMillis()
        );

        return clock.millis() > expiresAt;
    }

    private static Duration requirePositiveTimeout(
            Duration responseTimeout
    ) {
        Duration timeout = Objects.requireNonNull(
                responseTimeout,
                "responseTimeout cannot be null"
        );

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "responseTimeout must be positive"
            );
        }

        return timeout;
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

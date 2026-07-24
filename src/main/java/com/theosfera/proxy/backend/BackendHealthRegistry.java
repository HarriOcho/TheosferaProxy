package com.theosfera.proxy.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendHealthRegistry {

    private final Clock clock;
    private final Duration freshnessThreshold;
    private final Map<String, Instant> lastHealthyActivity =
            new ConcurrentHashMap<>();

    public BackendHealthRegistry(
            Clock clock,
            Duration freshnessThreshold
    ) {
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.freshnessThreshold = requirePositiveThreshold(
                freshnessThreshold
        );
    }

    public void markHealthy(String serverName) {
        lastHealthyActivity.put(
                requireServerName(serverName),
                clock.instant()
        );
    }

    public BackendHealthStatus status(String serverName) {
        Instant lastActivity = lastHealthyActivity.get(
                requireServerName(serverName)
        );

        if (lastActivity == null) {
            return BackendHealthStatus.UNKNOWN;
        }

        Instant staleAfter = lastActivity.plus(
                freshnessThreshold
        );

        return clock.instant().isAfter(staleAfter)
                ? BackendHealthStatus.STALE
                : BackendHealthStatus.HEALTHY;
    }

    public Optional<Instant> lastHealthyActivity(
            String serverName
    ) {
        return Optional.ofNullable(
                lastHealthyActivity.get(
                        requireServerName(serverName)
                )
        );
    }

    public Map<String, Instant> snapshot() {
        return Map.copyOf(lastHealthyActivity);
    }

    public void remove(String serverName) {
        lastHealthyActivity.remove(
                requireServerName(serverName)
        );
    }

    public void clear() {
        lastHealthyActivity.clear();
    }

    private static Duration requirePositiveThreshold(
            Duration freshnessThreshold
    ) {
        Duration threshold = Objects.requireNonNull(
                freshnessThreshold,
                "freshnessThreshold cannot be null"
        );

        if (threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException(
                    "freshnessThreshold must be positive"
            );
        }

        return threshold;
    }

    private static String requireServerName(
            String serverName
    ) {
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

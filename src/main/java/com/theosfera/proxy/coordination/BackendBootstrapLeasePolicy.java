package com.theosfera.proxy.coordination;

import java.time.Duration;
import java.util.Objects;

/**
 * Lease timing policy for one distributed backend bootstrap operation.
 *
 * <p>The TTL is a failure-detection window, not a maximum backend startup
 * duration. An active bootstrap owner is expected to renew before the TTL
 * expires for as long as the orchestration operation remains in progress.</p>
 */
public record BackendBootstrapLeasePolicy(
        Duration ttl,
        Duration renewInterval
) {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    public static final Duration DEFAULT_RENEW_INTERVAL =
            Duration.ofSeconds(20);

    public BackendBootstrapLeasePolicy {
        ttl = requirePositive(ttl, "ttl");
        renewInterval = requirePositive(
                renewInterval,
                "renewInterval"
        );

        if (renewInterval.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException(
                    "renewInterval must be shorter than ttl"
            );
        }
    }

    public static BackendBootstrapLeasePolicy productDefaults() {
        return new BackendBootstrapLeasePolicy(
                DEFAULT_TTL,
                DEFAULT_RENEW_INTERVAL
        );
    }

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        Duration nonNullValue = Objects.requireNonNull(
                value,
                name + " cannot be null"
        );

        if (nonNullValue.isZero()
                || nonNullValue.isNegative()
                || nonNullValue.toMillis() <= 0L) {
            throw new IllegalArgumentException(
                    name + " must be positive and at least one millisecond"
            );
        }

        return nonNullValue;
    }
}

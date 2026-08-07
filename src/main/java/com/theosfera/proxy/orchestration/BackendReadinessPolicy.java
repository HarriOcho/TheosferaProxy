package com.theosfera.proxy.orchestration;

import java.time.Duration;
import java.util.Objects;

public record BackendReadinessPolicy(
        Duration timeout,
        Duration pollInterval
) {

    public BackendReadinessPolicy {
        timeout = requirePositive(timeout, "timeout");
        pollInterval = requirePositive(pollInterval, "pollInterval");

        if (pollInterval.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException(
                    "pollInterval must be shorter than timeout"
            );
        }
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

package com.theosfera.proxy.orchestration;

import java.time.Duration;
import java.util.Objects;

/**
 * Timing policy for the provider-acceptance phase of backend startup.
 *
 * <p>This timeout is independent from the distributed bootstrap lease TTL.
 * Bootstrap ownership may continue renewing while this operation remains
 * active.</p>
 */
public record BackendStartupPolicy(
        Duration timeout,
        Duration initialRetryDelay,
        Duration maxRetryDelay
) {

    public BackendStartupPolicy {
        timeout = requirePositive(timeout, "timeout");
        initialRetryDelay = requirePositive(
                initialRetryDelay,
                "initialRetryDelay"
        );
        maxRetryDelay = requirePositive(
                maxRetryDelay,
                "maxRetryDelay"
        );

        if (initialRetryDelay.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException(
                    "initialRetryDelay cannot exceed maxRetryDelay"
            );
        }
        if (maxRetryDelay.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException(
                    "maxRetryDelay must be shorter than timeout"
            );
        }
    }

    public Duration retryDelay(int retryNumber) {
        if (retryNumber <= 0) {
            throw new IllegalArgumentException(
                    "retryNumber must be greater than zero"
            );
        }

        long currentMillis = initialRetryDelay.toMillis();
        long maxMillis = maxRetryDelay.toMillis();

        for (int index = 1;
             index < retryNumber && currentMillis < maxMillis;
             index++) {
            if (currentMillis > maxMillis / 2L) {
                currentMillis = maxMillis;
            } else {
                currentMillis = Math.min(
                        maxMillis,
                        Math.multiplyExact(currentMillis, 2L)
                );
            }
        }

        return Duration.ofMillis(currentMillis);
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

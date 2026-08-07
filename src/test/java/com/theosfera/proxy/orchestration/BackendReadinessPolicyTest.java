package com.theosfera.proxy.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendReadinessPolicyTest {

    @Test
    void retainsExplicitTimingPolicy() {
        BackendReadinessPolicy policy = new BackendReadinessPolicy(
                Duration.ofSeconds(45),
                Duration.ofSeconds(1)
        );

        assertEquals(Duration.ofSeconds(45), policy.timeout());
        assertEquals(Duration.ofSeconds(1), policy.pollInterval());
    }

    @Test
    void requiresPositiveDurations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendReadinessPolicy(
                        Duration.ZERO,
                        Duration.ofSeconds(1)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendReadinessPolicy(
                        Duration.ofSeconds(45),
                        Duration.ZERO
                )
        );
    }

    @Test
    void pollIntervalMustBeShorterThanTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendReadinessPolicy(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)
                )
        );
    }
}

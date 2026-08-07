package com.theosfera.proxy.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendStartupPolicyTest {

    @Test
    void computesCappedExponentialRetryDelay() {
        BackendStartupPolicy policy = new BackendStartupPolicy(
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofSeconds(4)
        );

        assertEquals(Duration.ofSeconds(1), policy.retryDelay(1));
        assertEquals(Duration.ofSeconds(2), policy.retryDelay(2));
        assertEquals(Duration.ofSeconds(4), policy.retryDelay(3));
        assertEquals(Duration.ofSeconds(4), policy.retryDelay(4));
        assertEquals(Duration.ofSeconds(4), policy.retryDelay(20));
    }

    @Test
    void rejectsInvalidTimingPolicy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendStartupPolicy(
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendStartupPolicy(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(4)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendStartupPolicy(
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(4)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendStartupPolicy(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(4)
                ).retryDelay(0)
        );
    }
}

package com.theosfera.proxy.coordination;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendBootstrapLeasePolicyTest {

    @Test
    void productDefaultsUseThreeToOneLeaseWindow() {
        BackendBootstrapLeasePolicy policy =
                BackendBootstrapLeasePolicy.productDefaults();

        assertEquals(Duration.ofSeconds(60), policy.ttl());
        assertEquals(
                Duration.ofSeconds(20),
                policy.renewInterval()
        );
    }

    @Test
    void rejectsRenewIntervalEqualToTtl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendBootstrapLeasePolicy(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30)
                )
        );
    }

    @Test
    void rejectsRenewIntervalLongerThanTtl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendBootstrapLeasePolicy(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(31)
                )
        );
    }

    @Test
    void rejectsSubMillisecondDurations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendBootstrapLeasePolicy(
                        Duration.ofNanos(1),
                        Duration.ofMillis(1)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendBootstrapLeasePolicy(
                        Duration.ofSeconds(1),
                        Duration.ofNanos(1)
                )
        );
    }
}

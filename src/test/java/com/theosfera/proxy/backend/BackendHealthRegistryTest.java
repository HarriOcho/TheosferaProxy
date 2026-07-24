package com.theosfera.proxy.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendHealthRegistryTest {

    private static final Instant INITIAL_TIME =
            Instant.parse("2026-07-24T06:00:00Z");
    private static final Duration FRESHNESS_THRESHOLD =
            Duration.ofSeconds(15);

    private MutableClock clock;
    private BackendHealthRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(INITIAL_TIME);
        registry = new BackendHealthRegistry(
                clock,
                FRESHNESS_THRESHOLD
        );
    }

    @Test
    void reportsUnknownBackend() {
        assertEquals(
                BackendHealthStatus.UNKNOWN,
                registry.status("lobby-1")
        );
        assertTrue(
                registry.lastHealthyActivity("lobby-1").isEmpty()
        );
    }

    @Test
    void marksBackendHealthyAtCurrentTime() {
        registry.markHealthy("lobby-1");

        assertEquals(
                BackendHealthStatus.HEALTHY,
                registry.status("lobby-1")
        );
        assertEquals(
                INITIAL_TIME,
                registry.lastHealthyActivity("lobby-1")
                        .orElseThrow()
        );
    }

    @Test
    void remainsHealthyAtExactFreshnessBoundary() {
        registry.markHealthy("lobby-1");

        clock.advance(FRESHNESS_THRESHOLD);

        assertEquals(
                BackendHealthStatus.HEALTHY,
                registry.status("lobby-1")
        );
    }

    @Test
    void becomesStaleAfterFreshnessBoundary() {
        registry.markHealthy("lobby-1");

        clock.advance(
                FRESHNESS_THRESHOLD.plusMillis(1)
        );

        assertEquals(
                BackendHealthStatus.STALE,
                registry.status("lobby-1")
        );
    }

    @Test
    void newHealthyActivityRefreshesStaleBackend() {
        registry.markHealthy("lobby-1");
        clock.advance(
                FRESHNESS_THRESHOLD.plusSeconds(1)
        );

        assertEquals(
                BackendHealthStatus.STALE,
                registry.status("lobby-1")
        );

        registry.markHealthy("lobby-1");

        assertEquals(
                BackendHealthStatus.HEALTHY,
                registry.status("lobby-1")
        );
        assertEquals(
                clock.instant(),
                registry.lastHealthyActivity("lobby-1")
                        .orElseThrow()
        );
    }

    @Test
    void tracksBackendsIndependently() {
        registry.markHealthy("lobby-1");
        clock.advance(
                FRESHNESS_THRESHOLD.plusSeconds(1)
        );
        registry.markHealthy("skyblock-1");

        assertEquals(
                BackendHealthStatus.STALE,
                registry.status("lobby-1")
        );
        assertEquals(
                BackendHealthStatus.HEALTHY,
                registry.status("skyblock-1")
        );
    }

    @Test
    void removesBackendHealth() {
        registry.markHealthy("lobby-1");

        registry.remove("lobby-1");

        assertEquals(
                BackendHealthStatus.UNKNOWN,
                registry.status("lobby-1")
        );
    }

    @Test
    void exposesImmutableSnapshot() {
        registry.markHealthy("lobby-1");

        Map<String, Instant> snapshot = registry.snapshot();

        assertEquals(INITIAL_TIME, snapshot.get("lobby-1"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.put("auth-1", INITIAL_TIME)
        );
    }

    @Test
    void snapshotDoesNotChangeAfterNewActivity() {
        registry.markHealthy("lobby-1");
        Map<String, Instant> snapshot = registry.snapshot();

        clock.advance(Duration.ofSeconds(1));
        registry.markHealthy("skyblock-1");

        assertFalse(snapshot.containsKey("skyblock-1"));
    }

    @Test
    void clearsAllBackendHealth() {
        registry.markHealthy("lobby-1");
        registry.markHealthy("skyblock-1");

        registry.clear();

        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void rejectsInvalidConstructionArguments() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendHealthRegistry(
                        null,
                        FRESHNESS_THRESHOLD
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new BackendHealthRegistry(clock, null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendHealthRegistry(
                        clock,
                        Duration.ZERO
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendHealthRegistry(
                        clock,
                        Duration.ofSeconds(-1)
                )
        );
    }

    @Test
    void rejectsInvalidServerNames() {
        assertThrows(
                NullPointerException.class,
                () -> registry.markHealthy(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.status("   ")
        );
        assertThrows(
                NullPointerException.class,
                () -> registry.lastHealthyActivity(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.remove("")
        );
    }

    private static final class MutableClock extends Clock {

        private Instant currentInstant;

        private MutableClock(Instant initialInstant) {
            this.currentInstant = initialInstant;
        }

        private void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (ZoneOffset.UTC.equals(zone)) {
                return this;
            }

            return Clock.fixed(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}

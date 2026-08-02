package com.theosfera.proxy.coordination;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyMembershipLifecycleTest {

    private static final ProxyInstanceIdentity IDENTITY =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString(
                            "11111111-2222-3333-4444-555555555555"
                    )
            );
    private static final ProxyMembershipLease LEASE =
            new ProxyMembershipLease(IDENTITY, 41L);

    @Test
    void successfulStartBecomesHealthyAndSchedulesRenewal() {
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.acquireResults.add(
                CompletableFuture.completedFuture(
                        ProxyMembershipAcquireResult.acquired(LEASE)
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        MutableClock clock = new MutableClock(1_000L);
        CoordinationStateRegistry states = new CoordinationStateRegistry();

        ProxyMembershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                states,
                clock
        );

        assertTrue(lifecycle.start(IDENTITY).toCompletableFuture().join());
        assertEquals(CoordinationState.HEALTHY, lifecycle.state());
        assertEquals(LEASE, lifecycle.currentLease());
        assertEquals(Duration.ofSeconds(5), scheduler.interval);
        assertFalse(scheduler.cancelled);
    }

    @Test
    void temporaryCoordinationFailureDegradesThenDeadlineFences() {
        FakeCoordinator coordinator = startedCoordinator();
        coordinator.renewResults.add(
                CompletableFuture.completedFuture(
                        ProxyMembershipRenewResult.withoutLease(
                                ProxyMembershipRenewResult.Status
                                        .COORDINATION_UNAVAILABLE
                        )
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        MutableClock clock = new MutableClock(1_000L);
        ProxyMembershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new CoordinationStateRegistry(),
                clock
        );

        assertTrue(lifecycle.start(IDENTITY).toCompletableFuture().join());
        scheduler.fire();
        assertEquals(CoordinationState.DEGRADED, lifecycle.state());

        clock.advance(Duration.ofSeconds(15));
        scheduler.fire();
        assertEquals(CoordinationState.FENCED, lifecycle.state());
        assertTrue(scheduler.cancelled);
    }

    @Test
    void explicitOwnershipLossFencesImmediately() {
        FakeCoordinator coordinator = startedCoordinator();
        coordinator.renewResults.add(
                CompletableFuture.completedFuture(
                        ProxyMembershipRenewResult.withoutLease(
                                ProxyMembershipRenewResult.Status.NOT_OWNER
                        )
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        ProxyMembershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new CoordinationStateRegistry(),
                new MutableClock(1_000L)
        );

        assertTrue(lifecycle.start(IDENTITY).toCompletableFuture().join());
        scheduler.fire();

        assertEquals(CoordinationState.FENCED, lifecycle.state());
        assertTrue(scheduler.cancelled);
    }

    @Test
    void stopCancelsRenewalAndReleasesExactLease() {
        FakeCoordinator coordinator = startedCoordinator();
        coordinator.releaseResult = CompletableFuture.completedFuture(true);
        FakeScheduler scheduler = new FakeScheduler();
        ProxyMembershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new CoordinationStateRegistry(),
                new MutableClock(1_000L)
        );

        assertTrue(lifecycle.start(IDENTITY).toCompletableFuture().join());
        assertTrue(lifecycle.stop().toCompletableFuture().join());

        assertEquals(CoordinationState.STOPPING, lifecycle.state());
        assertTrue(scheduler.cancelled);
        assertEquals(LEASE, coordinator.releasedLease);
        assertNull(lifecycle.currentLease());
    }

    @Test
    void lateRenewCompletionAfterStopCannotRestoreHealthy() {
        FakeCoordinator coordinator = startedCoordinator();
        CompletableFuture<ProxyMembershipRenewResult> pendingRenew =
                new CompletableFuture<>();
        coordinator.renewResults.add(pendingRenew);
        coordinator.releaseResult = CompletableFuture.completedFuture(true);
        FakeScheduler scheduler = new FakeScheduler();
        ProxyMembershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new CoordinationStateRegistry(),
                new MutableClock(1_000L)
        );

        assertTrue(lifecycle.start(IDENTITY).toCompletableFuture().join());
        scheduler.fire();
        assertTrue(lifecycle.stop().toCompletableFuture().join());

        pendingRenew.complete(ProxyMembershipRenewResult.renewed(LEASE));

        assertEquals(CoordinationState.STOPPING, lifecycle.state());
        assertNull(lifecycle.currentLease());
    }

    private static ProxyMembershipLifecycle lifecycle(
            FakeCoordinator coordinator,
            FakeScheduler scheduler,
            CoordinationStateRegistry states,
            Clock clock
    ) {
        return new ProxyMembershipLifecycle(
                coordinator,
                scheduler,
                states,
                clock,
                Duration.ofSeconds(15),
                Duration.ofSeconds(5)
        );
    }

    private static FakeCoordinator startedCoordinator() {
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.acquireResults.add(
                CompletableFuture.completedFuture(
                        ProxyMembershipAcquireResult.acquired(LEASE)
                )
        );
        return coordinator;
    }

    private static final class FakeCoordinator
            implements ProxyMembershipCoordinator {

        private final Queue<CompletionStage<ProxyMembershipAcquireResult>>
                acquireResults = new ArrayDeque<>();
        private final Queue<CompletionStage<ProxyMembershipRenewResult>>
                renewResults = new ArrayDeque<>();
        private CompletionStage<Boolean> releaseResult =
                CompletableFuture.completedFuture(true);
        private ProxyMembershipLease releasedLease;

        @Override
        public CompletionStage<ProxyMembershipAcquireResult> acquire(
                ProxyInstanceIdentity identity
        ) {
            return acquireResults.remove();
        }

        @Override
        public CompletionStage<ProxyMembershipRenewResult> renew(
                ProxyMembershipLease expected
        ) {
            return renewResults.remove();
        }

        @Override
        public CompletionStage<Boolean> releaseIfOwned(
                ProxyMembershipLease expected
        ) {
            releasedLease = expected;
            return releaseResult;
        }
    }

    private static final class FakeScheduler
            implements ProxyMembershipRenewalScheduler {

        private Runnable task;
        private Duration interval;
        private boolean cancelled;

        @Override
        public Handle schedule(Runnable task, Duration interval) {
            this.task = task;
            this.interval = interval;
            return () -> cancelled = true;
        }

        void fire() {
            if (!cancelled) {
                task.run();
            }
        }
    }

    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}

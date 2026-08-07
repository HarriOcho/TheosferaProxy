package com.theosfera.proxy.coordination;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendBootstrapOwnershipLifecycleTest {

    private static final ProxyInstanceIdentity PROXY_IDENTITY =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString(
                            "11111111-1111-1111-1111-111111111111"
                    )
            );
    private static final ProxyMembershipLease MEMBERSHIP =
            new ProxyMembershipLease(PROXY_IDENTITY, 7L);
    private static final UUID REQUEST_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );
    private static final UUID PLAYER_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
    );
    private static final BackendBootstrapAcquireRequest REQUEST =
            new BackendBootstrapAcquireRequest(
                    "lobby-2",
                    REQUEST_ID,
                    PLAYER_ID,
                    MEMBERSHIP
            );
    private static final BackendBootstrapLease LEASE =
            new BackendBootstrapLease(
                    "lobby-2",
                    REQUEST_ID,
                    PLAYER_ID,
                    MEMBERSHIP,
                    41L
            );
    private static final BackendBootstrapLeasePolicy POLICY =
            new BackendBootstrapLeasePolicy(
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(20)
            );

    @Test
    void successfulAcquireOwnsLeaseAndSchedulesRenewal() {
        FakeCoordinator coordinator = acquiredCoordinator();
        FakeScheduler scheduler = new FakeScheduler();
        MutableClock clock = new MutableClock(1_000L);
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                clock
        );

        BackendBootstrapAcquireResult result = lifecycle
                .start(REQUEST)
                .toCompletableFuture()
                .join();

        assertEquals(BackendBootstrapAcquireResult.Status.ACQUIRED, result.status());
        assertEquals(BackendBootstrapOwnershipState.OWNED, lifecycle.state());
        assertEquals(LEASE, lifecycle.currentLease());
        assertTrue(lifecycle.hasAuthority());
        assertEquals(Duration.ofSeconds(20), scheduler.interval);
        assertFalse(scheduler.cancelled);
        assertFalse(lifecycle.termination().toCompletableFuture().isDone());
    }

    @Test
    void temporaryCoordinationFailureDegradesThenDeadlineFences() {
        FakeCoordinator coordinator = acquiredCoordinator();
        coordinator.renewResults.add(
                CompletableFuture.completedFuture(
                        BackendBootstrapRenewResult.withoutLease(
                                BackendBootstrapRenewResult.Status
                                        .COORDINATION_UNAVAILABLE
                        )
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        MutableClock clock = new MutableClock(1_000L);
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                clock
        );

        lifecycle.start(REQUEST).toCompletableFuture().join();
        scheduler.fire();

        assertEquals(BackendBootstrapOwnershipState.DEGRADED, lifecycle.state());
        assertTrue(lifecycle.hasAuthority());

        clock.advance(Duration.ofSeconds(60));

        assertFalse(lifecycle.hasAuthority());
        assertEquals(BackendBootstrapOwnershipState.FENCED, lifecycle.state());
        assertNull(lifecycle.currentLease());
        assertTrue(scheduler.cancelled);
        assertEquals(
                BackendBootstrapOwnershipState.FENCED,
                lifecycle.termination().toCompletableFuture().join()
        );
    }

    @Test
    void explicitMembershipLossFencesImmediately() {
        FakeCoordinator coordinator = acquiredCoordinator();
        coordinator.renewResults.add(
                CompletableFuture.completedFuture(
                        BackendBootstrapRenewResult.withoutLease(
                                BackendBootstrapRenewResult.Status
                                        .NOT_MEMBERSHIP_OWNER
                        )
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new MutableClock(1_000L)
        );

        lifecycle.start(REQUEST).toCompletableFuture().join();
        scheduler.fire();

        assertEquals(BackendBootstrapOwnershipState.FENCED, lifecycle.state());
        assertFalse(lifecycle.hasAuthority());
        assertTrue(scheduler.cancelled);
        assertEquals(
                BackendBootstrapOwnershipState.FENCED,
                lifecycle.termination().toCompletableFuture().join()
        );
    }

    @Test
    void exactRenewalRestoresOwnedAndExtendsDeadline() {
        FakeCoordinator coordinator = acquiredCoordinator();
        coordinator.renewResults.add(
                CompletableFuture.completedFuture(
                        BackendBootstrapRenewResult.withoutLease(
                                BackendBootstrapRenewResult.Status
                                        .COORDINATION_UNAVAILABLE
                        )
                )
        );
        coordinator.renewResults.add(
                CompletableFuture.completedFuture(
                        BackendBootstrapRenewResult.renewed(LEASE)
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        MutableClock clock = new MutableClock(1_000L);
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                clock
        );

        lifecycle.start(REQUEST).toCompletableFuture().join();
        scheduler.fire();
        assertEquals(BackendBootstrapOwnershipState.DEGRADED, lifecycle.state());

        clock.advance(Duration.ofSeconds(20));
        scheduler.fire();

        assertEquals(BackendBootstrapOwnershipState.OWNED, lifecycle.state());
        assertTrue(lifecycle.hasAuthority());

        clock.advance(Duration.ofSeconds(59));
        assertTrue(lifecycle.hasAuthority());
    }

    @Test
    void stopCancelsRenewalAndReleasesExactLease() {
        FakeCoordinator coordinator = acquiredCoordinator();
        coordinator.releaseResult = CompletableFuture.completedFuture(
                new BackendBootstrapReleaseResult(
                        BackendBootstrapReleaseResult.Status.RELEASED
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new MutableClock(1_000L)
        );

        lifecycle.start(REQUEST).toCompletableFuture().join();
        Optional<BackendBootstrapReleaseResult> release = lifecycle
                .stop()
                .toCompletableFuture()
                .join();

        assertTrue(release.isPresent());
        assertEquals(
                BackendBootstrapReleaseResult.Status.RELEASED,
                release.orElseThrow().status()
        );
        assertEquals(LEASE, coordinator.releasedLease);
        assertEquals(BackendBootstrapOwnershipState.STOPPED, lifecycle.state());
        assertFalse(lifecycle.hasAuthority());
        assertNull(lifecycle.currentLease());
        assertTrue(scheduler.cancelled);
        assertEquals(
                BackendBootstrapOwnershipState.STOPPED,
                lifecycle.termination().toCompletableFuture().join()
        );
    }

    @Test
    void stopDuringAcquireReleasesLateLeaseBeforeCompletingStop() {
        FakeCoordinator coordinator = new FakeCoordinator();
        CompletableFuture<BackendBootstrapAcquireResult> pendingAcquire =
                new CompletableFuture<>();
        coordinator.acquireResults.add(pendingAcquire);
        coordinator.releaseResult = CompletableFuture.completedFuture(
                new BackendBootstrapReleaseResult(
                        BackendBootstrapReleaseResult.Status.RELEASED
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new MutableClock(1_000L)
        );

        CompletableFuture<BackendBootstrapAcquireResult> start = lifecycle
                .start(REQUEST)
                .toCompletableFuture();
        CompletableFuture<Optional<BackendBootstrapReleaseResult>> stop =
                lifecycle.stop().toCompletableFuture();

        assertEquals(BackendBootstrapOwnershipState.STOPPING, lifecycle.state());
        assertFalse(stop.isDone());

        pendingAcquire.complete(
                BackendBootstrapAcquireResult.withLease(
                        BackendBootstrapAcquireResult.Status.ACQUIRED,
                        LEASE
                )
        );

        assertTrue(start.isCompletedExceptionally());
        assertEquals(
                BackendBootstrapReleaseResult.Status.RELEASED,
                stop.join().orElseThrow().status()
        );
        assertEquals(LEASE, coordinator.releasedLease);
        assertEquals(BackendBootstrapOwnershipState.STOPPED, lifecycle.state());
        assertEquals(0, scheduler.scheduleCount);
    }

    @Test
    void lateRenewCompletionAfterStopCannotRestoreOwnership() {
        FakeCoordinator coordinator = acquiredCoordinator();
        CompletableFuture<BackendBootstrapRenewResult> pendingRenew =
                new CompletableFuture<>();
        coordinator.renewResults.add(pendingRenew);
        coordinator.releaseResult = CompletableFuture.completedFuture(
                new BackendBootstrapReleaseResult(
                        BackendBootstrapReleaseResult.Status.RELEASED
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new MutableClock(1_000L)
        );

        lifecycle.start(REQUEST).toCompletableFuture().join();
        scheduler.fire();
        lifecycle.stop().toCompletableFuture().join();

        pendingRenew.complete(BackendBootstrapRenewResult.renewed(LEASE));

        assertEquals(BackendBootstrapOwnershipState.STOPPED, lifecycle.state());
        assertFalse(lifecycle.hasAuthority());
        assertNull(lifecycle.currentLease());
    }

    @Test
    void negativeAcquireStopsWithoutSchedulingOrRelease() {
        FakeCoordinator coordinator = new FakeCoordinator();
        BackendBootstrapAcquireResult busy =
                BackendBootstrapAcquireResult.withoutLease(
                        BackendBootstrapAcquireResult.Status.TARGET_BUSY
                );
        coordinator.acquireResults.add(
                CompletableFuture.completedFuture(busy)
        );
        FakeScheduler scheduler = new FakeScheduler();
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new MutableClock(1_000L)
        );

        BackendBootstrapAcquireResult result = lifecycle
                .start(REQUEST)
                .toCompletableFuture()
                .join();

        assertSame(busy, result);
        assertEquals(BackendBootstrapOwnershipState.STOPPED, lifecycle.state());
        assertEquals(0, scheduler.scheduleCount);
        assertNull(coordinator.releasedLease);
        assertEquals(
                BackendBootstrapOwnershipState.STOPPED,
                lifecycle.termination().toCompletableFuture().join()
        );
    }

    @Test
    void schedulingFailureFencesAndAttemptsExactRelease() {
        FakeCoordinator coordinator = acquiredCoordinator();
        coordinator.releaseResult = CompletableFuture.completedFuture(
                new BackendBootstrapReleaseResult(
                        BackendBootstrapReleaseResult.Status.RELEASED
                )
        );
        FakeScheduler scheduler = new FakeScheduler();
        scheduler.scheduleFailure = new IllegalStateException(
                "scheduler unavailable"
        );
        BackendBootstrapOwnershipLifecycle lifecycle = lifecycle(
                coordinator,
                scheduler,
                new MutableClock(1_000L)
        );

        CompletableFuture<BackendBootstrapAcquireResult> start = lifecycle
                .start(REQUEST)
                .toCompletableFuture();

        assertTrue(start.isCompletedExceptionally());
        assertEquals(BackendBootstrapOwnershipState.FENCED, lifecycle.state());
        assertEquals(LEASE, coordinator.releasedLease);
        assertFalse(lifecycle.hasAuthority());
        assertEquals(
                BackendBootstrapOwnershipState.FENCED,
                lifecycle.termination().toCompletableFuture().join()
        );
    }

    private static BackendBootstrapOwnershipLifecycle lifecycle(
            FakeCoordinator coordinator,
            FakeScheduler scheduler,
            Clock clock
    ) {
        return new BackendBootstrapOwnershipLifecycle(
                coordinator,
                scheduler,
                clock,
                POLICY
        );
    }

    private static FakeCoordinator acquiredCoordinator() {
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.acquireResults.add(
                CompletableFuture.completedFuture(
                        BackendBootstrapAcquireResult.withLease(
                                BackendBootstrapAcquireResult.Status.ACQUIRED,
                                LEASE
                        )
                )
        );
        return coordinator;
    }

    private static final class FakeCoordinator
            implements BackendBootstrapCoordinator {

        private final Queue<CompletionStage<BackendBootstrapAcquireResult>>
                acquireResults = new ArrayDeque<>();
        private final Queue<CompletionStage<BackendBootstrapRenewResult>>
                renewResults = new ArrayDeque<>();
        private CompletionStage<BackendBootstrapReleaseResult> releaseResult =
                CompletableFuture.completedFuture(
                        new BackendBootstrapReleaseResult(
                                BackendBootstrapReleaseResult.Status.RELEASED
                        )
                );
        private BackendBootstrapLease releasedLease;

        @Override
        public CompletionStage<BackendBootstrapAcquireResult> acquire(
                BackendBootstrapAcquireRequest request
        ) {
            return acquireResults.remove();
        }

        @Override
        public CompletionStage<BackendBootstrapRenewResult> renew(
                BackendBootstrapLease expected
        ) {
            return renewResults.remove();
        }

        @Override
        public CompletionStage<BackendBootstrapReleaseResult> releaseIfOwned(
                BackendBootstrapLease expected
        ) {
            releasedLease = expected;
            return releaseResult;
        }
    }

    private static final class FakeScheduler
            implements BackendBootstrapRenewalScheduler {

        private Runnable task;
        private Duration interval;
        private boolean cancelled;
        private int scheduleCount;
        private RuntimeException scheduleFailure;

        @Override
        public Handle schedule(Runnable task, Duration interval) {
            if (scheduleFailure != null) {
                throw scheduleFailure;
            }
            this.task = task;
            this.interval = interval;
            ++scheduleCount;
            return () -> cancelled = true;
        }

        void fire() {
            if (!cancelled && task != null) {
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

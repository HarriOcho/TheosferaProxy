package com.theosfera.proxy.coordination;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ProxyMembershipLifecycle {

    public static final Duration DEFAULT_MEMBERSHIP_TTL =
            Duration.ofSeconds(15);
    public static final Duration DEFAULT_RENEW_INTERVAL =
            Duration.ofSeconds(5);

    private final ProxyMembershipCoordinator coordinator;
    private final ProxyMembershipRenewalScheduler scheduler;
    private final CoordinationStateRegistry stateRegistry;
    private final Clock clock;
    private final Duration membershipTtl;
    private final Duration renewInterval;
    private final Object lock = new Object();

    private long lifecycleEpoch;
    private ProxyMembershipLease lease;
    private ProxyMembershipRenewalScheduler.Handle renewalHandle;
    private boolean renewInFlight;
    private long ownershipDeadlineMillis;

    public ProxyMembershipLifecycle(
            ProxyMembershipCoordinator coordinator,
            ProxyMembershipRenewalScheduler scheduler,
            CoordinationStateRegistry stateRegistry,
            Clock clock
    ) {
        this(
                coordinator,
                scheduler,
                stateRegistry,
                clock,
                DEFAULT_MEMBERSHIP_TTL,
                DEFAULT_RENEW_INTERVAL
        );
    }

    public ProxyMembershipLifecycle(
            ProxyMembershipCoordinator coordinator,
            ProxyMembershipRenewalScheduler scheduler,
            CoordinationStateRegistry stateRegistry,
            Clock clock,
            Duration membershipTtl,
            Duration renewInterval
    ) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator cannot be null"
        );
        this.scheduler = Objects.requireNonNull(
                scheduler,
                "scheduler cannot be null"
        );
        this.stateRegistry = Objects.requireNonNull(
                stateRegistry,
                "stateRegistry cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.membershipTtl = requirePositive(
                membershipTtl,
                "membershipTtl"
        );
        this.renewInterval = requirePositive(
                renewInterval,
                "renewInterval"
        );

        if (this.renewInterval.compareTo(this.membershipTtl) >= 0) {
            throw new IllegalArgumentException(
                    "renewInterval must be shorter than membershipTtl"
            );
        }
    }

    public CompletionStage<Boolean> start(
            ProxyInstanceIdentity identity
    ) {
        ProxyInstanceIdentity nonNullIdentity = Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );

        final long epoch;
        synchronized (lock) {
            if (stateRegistry.get() != CoordinationState.STARTING) {
                throw new IllegalStateException(
                        "membership lifecycle can only start from STARTING"
                );
            }
            epoch = ++lifecycleEpoch;
        }

        return coordinator.acquire(nonNullIdentity)
                .handle((result, failure) ->
                        startAfterAcquire(epoch, result, failure))
                .thenCompose(stage -> stage);
    }

    public CompletionStage<Boolean> stop() {
        ProxyMembershipLease leaseToRelease;
        ProxyMembershipRenewalScheduler.Handle handleToCancel;

        synchronized (lock) {
            ++lifecycleEpoch;
            stateRegistry.set(CoordinationState.STOPPING);
            handleToCancel = renewalHandle;
            renewalHandle = null;
            leaseToRelease = lease;
            lease = null;
            renewInFlight = false;
            ownershipDeadlineMillis = 0L;
        }

        if (handleToCancel != null) {
            handleToCancel.cancel();
        }

        if (leaseToRelease == null) {
            return CompletableFuture.completedFuture(true);
        }

        return coordinator.releaseIfOwned(leaseToRelease);
    }

    public CoordinationState state() {
        return stateRegistry.get();
    }

    public ProxyMembershipLease currentLease() {
        synchronized (lock) {
            return lease;
        }
    }

    private CompletionStage<Boolean> startAfterAcquire(
            long epoch,
            ProxyMembershipAcquireResult result,
            Throwable failure
    ) {
        if (failure != null) {
            fenceIfCurrent(epoch);
            return CompletableFuture.failedFuture(failure);
        }

        if (result == null) {
            fenceIfCurrent(epoch);
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "membership acquire returned null result"
                    )
            );
        }

        return switch (result.status()) {
            case ACQUIRED, ALREADY_OWNED ->
                    activateAcquiredLease(
                            epoch,
                            result.lease().orElseThrow()
                    );
            case OWNED_BY_OTHER_INCARNATION,
                    COORDINATION_UNAVAILABLE -> {
                fenceIfCurrent(epoch);
                yield CompletableFuture.completedFuture(false);
            }
        };
    }

    private CompletionStage<Boolean> activateAcquiredLease(
            long epoch,
            ProxyMembershipLease acquiredLease
    ) {
        ProxyMembershipRenewalScheduler.Handle scheduledHandle;

        synchronized (lock) {
            if (epoch != lifecycleEpoch
                    || stateRegistry.get() != CoordinationState.STARTING) {
                return coordinator.releaseIfOwned(acquiredLease);
            }

            lease = acquiredLease;
            ownershipDeadlineMillis = deadlineFromNow();

            try {
                scheduledHandle = scheduler.schedule(
                        () -> renewTick(epoch),
                        renewInterval
                );
            } catch (RuntimeException exception) {
                lease = null;
                ownershipDeadlineMillis = 0L;
                stateRegistry.set(CoordinationState.FENCED);
                ++lifecycleEpoch;
                return coordinator.releaseIfOwned(acquiredLease)
                        .thenCompose(ignored ->
                                CompletableFuture.failedFuture(exception));
            }

            renewalHandle = scheduledHandle;
            stateRegistry.set(CoordinationState.HEALTHY);
            return CompletableFuture.completedFuture(true);
        }
    }

    private void renewTick(long epoch) {
        ProxyMembershipLease expected;

        synchronized (lock) {
            if (epoch != lifecycleEpoch) {
                return;
            }

            CoordinationState state = stateRegistry.get();
            if (state != CoordinationState.HEALTHY
                    && state != CoordinationState.DEGRADED) {
                return;
            }

            if (clock.millis() >= ownershipDeadlineMillis) {
                fenceLocked();
                return;
            }

            if (renewInFlight) {
                return;
            }

            expected = lease;
            if (expected == null) {
                fenceLocked();
                return;
            }

            renewInFlight = true;
        }

        coordinator.renew(expected).whenComplete(
                (result, failure) ->
                        renewCompleted(epoch, expected, result, failure)
        );
    }

    private void renewCompleted(
            long epoch,
            ProxyMembershipLease expected,
            ProxyMembershipRenewResult result,
            Throwable failure
    ) {
        synchronized (lock) {
            if (epoch != lifecycleEpoch
                    || lease == null
                    || !lease.equals(expected)) {
                return;
            }

            renewInFlight = false;

            if (failure != null || result == null) {
                fenceLocked();
                return;
            }

            switch (result.status()) {
                case RENEWED -> {
                    ProxyMembershipLease renewed =
                            result.lease().orElseThrow();
                    if (!renewed.equals(expected)) {
                        fenceLocked();
                        return;
                    }
                    ownershipDeadlineMillis = deadlineFromNow();
                    stateRegistry.set(CoordinationState.HEALTHY);
                }
                case COORDINATION_UNAVAILABLE -> {
                    if (clock.millis() >= ownershipDeadlineMillis) {
                        fenceLocked();
                    } else {
                        stateRegistry.set(CoordinationState.DEGRADED);
                    }
                }
                case NOT_FOUND, NOT_OWNER, CONFLICT -> fenceLocked();
            }
        }
    }

    private void fenceIfCurrent(long epoch) {
        synchronized (lock) {
            if (epoch == lifecycleEpoch) {
                fenceLocked();
            }
        }
    }

    private void fenceLocked() {
        stateRegistry.set(CoordinationState.FENCED);
        ++lifecycleEpoch;
        renewInFlight = false;
        ownershipDeadlineMillis = 0L;

        if (renewalHandle != null) {
            renewalHandle.cancel();
            renewalHandle = null;
        }
    }

    private long deadlineFromNow() {
        return Math.addExact(clock.millis(), membershipTtl.toMillis());
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
                || nonNullValue.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }

        return nonNullValue;
    }
}

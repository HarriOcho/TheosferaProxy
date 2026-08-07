package com.theosfera.proxy.coordination;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Owns the distributed lease lifecycle for one backend bootstrap operation.
 *
 * <p>The lifecycle is single-use. A successful acquire is renewed until the
 * operation is stopped. Temporary coordination loss degrades authority only
 * until the last known lease deadline; explicit ownership or membership loss
 * fences immediately. Once fenced, callers must stop issuing authoritative
 * orchestration side effects for this bootstrap generation.</p>
 */
public final class BackendBootstrapOwnershipLifecycle {

    private final BackendBootstrapCoordinator coordinator;
    private final BackendBootstrapRenewalScheduler scheduler;
    private final Clock clock;
    private final BackendBootstrapLeasePolicy policy;
    private final Object lock = new Object();
    private final CompletableFuture<BackendBootstrapOwnershipState> termination =
            new CompletableFuture<>();

    private BackendBootstrapOwnershipState state =
            BackendBootstrapOwnershipState.NEW;
    private long lifecycleEpoch;
    private BackendBootstrapLease lease;
    private BackendBootstrapRenewalScheduler.Handle renewalHandle;
    private boolean renewInFlight;
    private long ownershipDeadlineMillis;
    private CompletableFuture<Optional<BackendBootstrapReleaseResult>> stopFuture;

    public BackendBootstrapOwnershipLifecycle(
            BackendBootstrapCoordinator coordinator,
            BackendBootstrapRenewalScheduler scheduler,
            Clock clock,
            BackendBootstrapLeasePolicy policy
    ) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator cannot be null"
        );
        this.scheduler = Objects.requireNonNull(
                scheduler,
                "scheduler cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.policy = Objects.requireNonNull(
                policy,
                "policy cannot be null"
        );
    }

    public CompletionStage<BackendBootstrapAcquireResult> start(
            BackendBootstrapAcquireRequest request
    ) {
        BackendBootstrapAcquireRequest nonNullRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );

        final long epoch;
        synchronized (lock) {
            if (state != BackendBootstrapOwnershipState.NEW) {
                throw new IllegalStateException(
                        "backend bootstrap ownership lifecycle is single-use"
                );
            }
            state = BackendBootstrapOwnershipState.ACQUIRING;
            epoch = ++lifecycleEpoch;
        }

        final CompletionStage<BackendBootstrapAcquireResult> acquireStage;
        try {
            acquireStage = coordinator.acquire(nonNullRequest);
        } catch (RuntimeException exception) {
            fenceAcquireFailure(epoch);
            return CompletableFuture.failedFuture(exception);
        }

        if (acquireStage == null) {
            IllegalStateException failure = new IllegalStateException(
                    "backend bootstrap acquire returned null stage"
            );
            fenceAcquireFailure(epoch);
            return CompletableFuture.failedFuture(failure);
        }

        return acquireStage
                .handle((result, failure) ->
                        startAfterAcquire(epoch, result, failure))
                .thenCompose(stage -> stage);
    }

    public CompletionStage<Optional<BackendBootstrapReleaseResult>> stop() {
        BackendBootstrapLease leaseToRelease = null;
        CompletableFuture<Optional<BackendBootstrapReleaseResult>> future;

        synchronized (lock) {
            if (stopFuture != null) {
                return stopFuture;
            }

            stopFuture = new CompletableFuture<>();
            future = stopFuture;

            switch (state) {
                case NEW -> {
                    state = BackendBootstrapOwnershipState.STOPPED;
                    completeTerminationLocked(
                            BackendBootstrapOwnershipState.STOPPED
                    );
                    future.complete(Optional.empty());
                    return future;
                }
                case ACQUIRING -> {
                    state = BackendBootstrapOwnershipState.STOPPING;
                    return future;
                }
                case OWNED, DEGRADED -> {
                    ++lifecycleEpoch;
                    state = BackendBootstrapOwnershipState.STOPPING;
                    cancelRenewalLocked();
                    leaseToRelease = lease;
                    lease = null;
                    renewInFlight = false;
                    ownershipDeadlineMillis = 0L;
                }
                case STOPPING -> {
                    return future;
                }
                case FENCED -> {
                    future.complete(Optional.empty());
                    return future;
                }
                case STOPPED -> {
                    future.complete(Optional.empty());
                    return future;
                }
            }
        }

        releaseForStop(leaseToRelease, future);
        return future;
    }

    public BackendBootstrapOwnershipState state() {
        synchronized (lock) {
            fenceIfDeadlineExpiredLocked();
            return state;
        }
    }

    public BackendBootstrapLease currentLease() {
        synchronized (lock) {
            fenceIfDeadlineExpiredLocked();
            return lease;
        }
    }

    public boolean hasAuthority() {
        synchronized (lock) {
            fenceIfDeadlineExpiredLocked();
            return (state == BackendBootstrapOwnershipState.OWNED
                    || state == BackendBootstrapOwnershipState.DEGRADED)
                    && lease != null;
        }
    }

    /**
     * Completes once this lifecycle reaches a terminal state.
     *
     * <p>{@link BackendBootstrapOwnershipState#FENCED} means authority was
     * lost or became unsafe and orchestration must abort. {@link
     * BackendBootstrapOwnershipState#STOPPED} is an intentional local stop.</p>
     */
    public CompletionStage<BackendBootstrapOwnershipState> termination() {
        return termination.thenApply(value -> value);
    }

    private CompletionStage<BackendBootstrapAcquireResult> startAfterAcquire(
            long epoch,
            BackendBootstrapAcquireResult result,
            Throwable failure
    ) {
        if (failure != null) {
            fenceAcquireFailure(epoch);
            return CompletableFuture.failedFuture(failure);
        }
        if (result == null) {
            IllegalStateException exception = new IllegalStateException(
                    "backend bootstrap acquire returned null result"
            );
            fenceAcquireFailure(epoch);
            return CompletableFuture.failedFuture(exception);
        }

        return switch (result.status()) {
            case ACQUIRED, ALREADY_OWNED -> activateAcquiredLease(
                    epoch,
                    result,
                    result.acquiredLease().orElseThrow(
                            () -> new IllegalStateException(
                                    "successful bootstrap acquire omitted lease"
                            )
                    )
            );
            case TARGET_BUSY,
                    REQUEST_ID_CONFLICT,
                    MEMBERSHIP_NOT_FOUND,
                    NOT_MEMBERSHIP_OWNER,
                    COORDINATION_UNAVAILABLE -> completeWithoutOwnership(
                    epoch,
                    result
            );
        };
    }

    private CompletionStage<BackendBootstrapAcquireResult> activateAcquiredLease(
            long epoch,
            BackendBootstrapAcquireResult result,
            BackendBootstrapLease acquiredLease
    ) {
        RuntimeException schedulingFailure = null;
        boolean releaseBecauseStopping = false;

        synchronized (lock) {
            if (epoch != lifecycleEpoch) {
                releaseBecauseStopping = true;
            } else if (state == BackendBootstrapOwnershipState.STOPPING) {
                releaseBecauseStopping = true;
            } else if (state != BackendBootstrapOwnershipState.ACQUIRING) {
                releaseBecauseStopping = true;
            } else {
                lease = acquiredLease;
                ownershipDeadlineMillis = deadlineFromNow();

                try {
                    renewalHandle = scheduler.schedule(
                            () -> renewTick(epoch),
                            policy.renewInterval()
                    );
                    if (renewalHandle == null) {
                        throw new IllegalStateException(
                                "backend bootstrap scheduler returned null handle"
                        );
                    }
                    state = BackendBootstrapOwnershipState.OWNED;
                } catch (RuntimeException exception) {
                    schedulingFailure = exception;
                    lease = null;
                    ownershipDeadlineMillis = 0L;
                    renewInFlight = false;
                    state = BackendBootstrapOwnershipState.FENCED;
                    ++lifecycleEpoch;
                    completeTerminationLocked(
                            BackendBootstrapOwnershipState.FENCED
                    );
                }
            }
        }

        if (releaseBecauseStopping) {
            return releaseAfterStoppedAcquire(result, acquiredLease);
        }

        if (schedulingFailure != null) {
            RuntimeException failure = schedulingFailure;
            return coordinator.releaseIfOwned(acquiredLease)
                    .handle((ignored, releaseFailure) -> {
                        if (releaseFailure != null) {
                            failure.addSuppressed(releaseFailure);
                        }
                        return failure;
                    })
                    .thenCompose(exception ->
                            CompletableFuture.failedFuture(exception));
        }

        return CompletableFuture.completedFuture(result);
    }

    private CompletionStage<BackendBootstrapAcquireResult>
    releaseAfterStoppedAcquire(
            BackendBootstrapAcquireResult result,
            BackendBootstrapLease acquiredLease
    ) {
        final CompletionStage<BackendBootstrapReleaseResult> releaseStage;
        try {
            releaseStage = coordinator.releaseIfOwned(acquiredLease);
        } catch (RuntimeException exception) {
            finishStoppedAcquire(null, exception);
            return CompletableFuture.failedFuture(exception);
        }

        if (releaseStage == null) {
            IllegalStateException failure = new IllegalStateException(
                    "backend bootstrap release returned null stage"
            );
            finishStoppedAcquire(null, failure);
            return CompletableFuture.failedFuture(failure);
        }

        return releaseStage.handle((releaseResult, releaseFailure) -> {
            finishStoppedAcquire(releaseResult, releaseFailure);

            if (releaseFailure != null) {
                return BackendBootstrapOwnershipLifecycle
                        .<BackendBootstrapAcquireResult>failed(releaseFailure);
            }
            if (releaseResult == null) {
                return BackendBootstrapOwnershipLifecycle
                        .<BackendBootstrapAcquireResult>failed(
                                new IllegalStateException(
                                        "backend bootstrap release returned null result"
                                )
                        );
            }

            CancellationException cancellation = new CancellationException(
                    "backend bootstrap ownership stopped during acquire"
            );
            return BackendBootstrapOwnershipLifecycle
                    .<BackendBootstrapAcquireResult>failed(cancellation);
        }).thenCompose(stage -> stage);
    }

    private CompletionStage<BackendBootstrapAcquireResult>
    completeWithoutOwnership(
            long epoch,
            BackendBootstrapAcquireResult result
    ) {
        boolean stoppedDuringAcquire;

        synchronized (lock) {
            if (epoch != lifecycleEpoch) {
                stoppedDuringAcquire = true;
            } else if (state == BackendBootstrapOwnershipState.STOPPING) {
                stoppedDuringAcquire = true;
                state = BackendBootstrapOwnershipState.STOPPED;
                completeTerminationLocked(
                        BackendBootstrapOwnershipState.STOPPED
                );
                completeStopWithoutReleaseLocked();
            } else if (state == BackendBootstrapOwnershipState.ACQUIRING) {
                stoppedDuringAcquire = false;
                state = BackendBootstrapOwnershipState.STOPPED;
                completeTerminationLocked(
                        BackendBootstrapOwnershipState.STOPPED
                );
            } else {
                stoppedDuringAcquire = true;
            }
        }

        if (stoppedDuringAcquire) {
            return failed(
                    new CancellationException(
                            "backend bootstrap ownership stopped during acquire"
                    )
            );
        }
        return CompletableFuture.completedFuture(result);
    }

    private void renewTick(long epoch) {
        BackendBootstrapLease expected;

        synchronized (lock) {
            if (epoch != lifecycleEpoch) {
                return;
            }
            if (state != BackendBootstrapOwnershipState.OWNED
                    && state != BackendBootstrapOwnershipState.DEGRADED) {
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

        final CompletionStage<BackendBootstrapRenewResult> renewStage;
        try {
            renewStage = coordinator.renew(expected);
        } catch (RuntimeException exception) {
            renewCompleted(epoch, expected, null, exception);
            return;
        }

        if (renewStage == null) {
            renewCompleted(
                    epoch,
                    expected,
                    null,
                    new IllegalStateException(
                            "backend bootstrap renew returned null stage"
                    )
            );
            return;
        }

        renewStage.whenComplete(
                (result, failure) ->
                        renewCompleted(epoch, expected, result, failure)
        );
    }

    private void renewCompleted(
            long epoch,
            BackendBootstrapLease expected,
            BackendBootstrapRenewResult result,
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
                    BackendBootstrapLease renewed = result.renewedLease()
                            .orElse(null);
                    if (!expected.equals(renewed)) {
                        fenceLocked();
                        return;
                    }
                    ownershipDeadlineMillis = deadlineFromNow();
                    state = BackendBootstrapOwnershipState.OWNED;
                }
                case COORDINATION_UNAVAILABLE -> {
                    if (clock.millis() >= ownershipDeadlineMillis) {
                        fenceLocked();
                    } else {
                        state = BackendBootstrapOwnershipState.DEGRADED;
                    }
                }
                case NOT_FOUND,
                        NOT_OWNER,
                        CONFLICT,
                        MEMBERSHIP_NOT_FOUND,
                        NOT_MEMBERSHIP_OWNER -> fenceLocked();
            }
        }
    }

    private void releaseForStop(
            BackendBootstrapLease leaseToRelease,
            CompletableFuture<Optional<BackendBootstrapReleaseResult>> future
    ) {
        if (leaseToRelease == null) {
            synchronized (lock) {
                state = BackendBootstrapOwnershipState.STOPPED;
                completeTerminationLocked(
                        BackendBootstrapOwnershipState.STOPPED
                );
            }
            future.complete(Optional.empty());
            return;
        }

        final CompletionStage<BackendBootstrapReleaseResult> releaseStage;
        try {
            releaseStage = coordinator.releaseIfOwned(leaseToRelease);
        } catch (RuntimeException exception) {
            finishStop(future, null, exception);
            return;
        }

        if (releaseStage == null) {
            finishStop(
                    future,
                    null,
                    new IllegalStateException(
                            "backend bootstrap release returned null stage"
                    )
            );
            return;
        }

        releaseStage.whenComplete(
                (result, failure) -> finishStop(future, result, failure)
        );
    }

    private void finishStop(
            CompletableFuture<Optional<BackendBootstrapReleaseResult>> future,
            BackendBootstrapReleaseResult result,
            Throwable failure
    ) {
        synchronized (lock) {
            state = BackendBootstrapOwnershipState.STOPPED;
            completeTerminationLocked(
                    BackendBootstrapOwnershipState.STOPPED
            );
        }

        if (failure != null) {
            future.completeExceptionally(failure);
        } else if (result == null) {
            future.completeExceptionally(
                    new IllegalStateException(
                            "backend bootstrap release returned null result"
                    )
            );
        } else {
            future.complete(Optional.of(result));
        }
    }

    private void finishStoppedAcquire(
            BackendBootstrapReleaseResult result,
            Throwable failure
    ) {
        CompletableFuture<Optional<BackendBootstrapReleaseResult>> future;

        synchronized (lock) {
            state = BackendBootstrapOwnershipState.STOPPED;
            lease = null;
            renewInFlight = false;
            ownershipDeadlineMillis = 0L;
            completeTerminationLocked(
                    BackendBootstrapOwnershipState.STOPPED
            );
            future = stopFuture;
        }

        if (future == null) {
            return;
        }

        if (failure != null) {
            future.completeExceptionally(failure);
        } else if (result == null) {
            future.completeExceptionally(
                    new IllegalStateException(
                            "backend bootstrap release returned null result"
                    )
            );
        } else {
            future.complete(Optional.of(result));
        }
    }

    private void fenceAcquireFailure(long epoch) {
        CompletableFuture<Optional<BackendBootstrapReleaseResult>> future = null;
        boolean stopped = false;

        synchronized (lock) {
            if (epoch != lifecycleEpoch) {
                return;
            }

            if (state == BackendBootstrapOwnershipState.STOPPING) {
                state = BackendBootstrapOwnershipState.STOPPED;
                stopped = true;
                future = stopFuture;
                completeTerminationLocked(
                        BackendBootstrapOwnershipState.STOPPED
                );
            } else if (state == BackendBootstrapOwnershipState.ACQUIRING) {
                fenceLocked();
            }
        }

        if (stopped && future != null) {
            future.complete(Optional.empty());
        }
    }

    private void fenceIfDeadlineExpiredLocked() {
        if ((state == BackendBootstrapOwnershipState.OWNED
                || state == BackendBootstrapOwnershipState.DEGRADED)
                && clock.millis() >= ownershipDeadlineMillis) {
            fenceLocked();
        }
    }

    private void fenceLocked() {
        state = BackendBootstrapOwnershipState.FENCED;
        ++lifecycleEpoch;
        lease = null;
        renewInFlight = false;
        ownershipDeadlineMillis = 0L;
        cancelRenewalLocked();
        completeTerminationLocked(
                BackendBootstrapOwnershipState.FENCED
        );
    }

    private void cancelRenewalLocked() {
        if (renewalHandle != null) {
            renewalHandle.cancel();
            renewalHandle = null;
        }
    }

    private void completeStopWithoutReleaseLocked() {
        if (stopFuture != null) {
            stopFuture.complete(Optional.empty());
        }
    }

    private void completeTerminationLocked(
            BackendBootstrapOwnershipState terminalState
    ) {
        termination.complete(terminalState);
    }

    private long deadlineFromNow() {
        return Math.addExact(clock.millis(), policy.ttl().toMillis());
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }
}

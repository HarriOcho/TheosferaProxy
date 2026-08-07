package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipLifecycle;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipState;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Waits for authoritative backend readiness after provider start acceptance.
 *
 * <p>Readiness requires the current control-channel identity plus fresh health
 * evidence. This lifecycle does not use process state or TCP reachability as
 * readiness authority.</p>
 */
public final class BackendReadinessLifecycle {

    private final BackendBootstrapOwnershipLifecycle ownershipLifecycle;
    private final BackendReadinessProbe readinessProbe;
    private final BackendReadinessScheduler scheduler;
    private final BackendReadinessPolicy policy;
    private final Object lock = new Object();
    private final CompletableFuture<BackendReadinessLifecycleState> completion =
            new CompletableFuture<>();

    private BackendReadinessLifecycleState state =
            BackendReadinessLifecycleState.NEW;
    private long lifecycleEpoch;
    private BackendBootstrapLease bootstrapLease;
    private BackendReadinessScheduler.Handle timeoutHandle;
    private BackendReadinessScheduler.Handle pollHandle;

    public BackendReadinessLifecycle(
            BackendBootstrapOwnershipLifecycle ownershipLifecycle,
            BackendReadinessProbe readinessProbe,
            BackendReadinessScheduler scheduler,
            BackendReadinessPolicy policy
    ) {
        this.ownershipLifecycle = Objects.requireNonNull(
                ownershipLifecycle,
                "ownershipLifecycle cannot be null"
        );
        this.readinessProbe = Objects.requireNonNull(
                readinessProbe,
                "readinessProbe cannot be null"
        );
        this.scheduler = Objects.requireNonNull(
                scheduler,
                "scheduler cannot be null"
        );
        this.policy = Objects.requireNonNull(
                policy,
                "policy cannot be null"
        );
    }

    public CompletionStage<BackendReadinessLifecycleState> start() {
        final long epoch;

        synchronized (lock) {
            if (state != BackendReadinessLifecycleState.NEW) {
                throw new IllegalStateException(
                        "backend readiness lifecycle is single-use"
                );
            }
            state = BackendReadinessLifecycleState.WAITING_CONTROL;
            epoch = ++lifecycleEpoch;
        }

        final CompletionStage<BackendBootstrapOwnershipState>
                ownershipTermination;
        try {
            ownershipTermination = Objects.requireNonNull(
                    ownershipLifecycle.termination(),
                    "ownership lifecycle returned null termination stage"
            );
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FAILED,
                    exception
            );
            return completion();
        }

        ownershipTermination.whenComplete(
                (terminalState, failure) ->
                        ownershipTerminated(epoch, terminalState, failure)
        );

        final BackendBootstrapLease currentLease;
        try {
            if (!ownershipLifecycle.hasAuthority()) {
                transitionWithoutCleanup(
                        epoch,
                        BackendReadinessLifecycleState.FENCED
                );
                return completion();
            }
            currentLease = ownershipLifecycle.currentLease();
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FAILED,
                    exception
            );
            return completion();
        }

        if (currentLease == null) {
            transitionWithoutCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FENCED
            );
            return completion();
        }

        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return completion();
            }
            bootstrapLease = currentLease;
        }

        if (!scheduleTimeout(epoch)) {
            return completion();
        }

        probeNow(epoch);
        return completion();
    }

    public CompletionStage<BackendReadinessLifecycleState> cancel() {
        final long epoch;

        synchronized (lock) {
            if (state == BackendReadinessLifecycleState.READY
                    || isTerminalState(state)) {
                return completion();
            }
            epoch = lifecycleEpoch;
        }

        transitionWithCleanup(
                epoch,
                BackendReadinessLifecycleState.CANCELLED,
                null
        );
        return completion();
    }

    public BackendReadinessLifecycleState state() {
        synchronized (lock) {
            return state;
        }
    }

    public CompletionStage<BackendReadinessLifecycleState> completion() {
        return completion.thenApply(value -> value);
    }

    private boolean scheduleTimeout(long epoch) {
        final BackendReadinessScheduler.Handle scheduledHandle;
        try {
            scheduledHandle = Objects.requireNonNull(
                    scheduler.schedule(
                            () -> timeoutReached(epoch),
                            policy.timeout()
                    ),
                    "backend readiness scheduler returned null timeout handle"
            );
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FAILED,
                    exception
            );
            return false;
        }

        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                scheduledHandle.cancel();
                return false;
            }
            timeoutHandle = scheduledHandle;
        }
        return true;
    }

    private void probeNow(long epoch) {
        final BackendBootstrapLease expectedLease;

        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return;
            }
            pollHandle = null;
            expectedLease = bootstrapLease;
        }

        if (expectedLease == null) {
            transitionWithoutCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FENCED
            );
            return;
        }

        try {
            if (!ownershipLifecycle.hasAuthority()
                    || !expectedLease.equals(
                            ownershipLifecycle.currentLease()
                    )) {
                transitionWithoutCleanup(
                        epoch,
                        BackendReadinessLifecycleState.FENCED
                );
                return;
            }
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FAILED,
                    exception
            );
            return;
        }

        final BackendReadinessSnapshot snapshot;
        try {
            snapshot = Objects.requireNonNull(
                    readinessProbe.check(
                            expectedLease.targetBackendName()
                    ),
                    "backend readiness probe returned null"
            );
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FAILED,
                    exception
            );
            return;
        }

        switch (snapshot.status()) {
            case READY -> completeReady(epoch);
            case CONTROL_NOT_AUTHENTICATED -> waitAndPoll(
                    epoch,
                    BackendReadinessLifecycleState.WAITING_CONTROL
            );
            case HEALTH_NOT_READY -> waitAndPoll(
                    epoch,
                    BackendReadinessLifecycleState.WAITING_HEALTH
            );
            case TARGET_NOT_CONFIGURED, IDENTITY_MISMATCH ->
                    transitionWithCleanup(
                            epoch,
                            BackendReadinessLifecycleState.FAILED,
                            null
                    );
        }
    }

    private void waitAndPoll(
            long epoch,
            BackendReadinessLifecycleState waitingState
    ) {
        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return;
            }
            state = waitingState;
        }

        final BackendReadinessScheduler.Handle scheduledHandle;
        try {
            scheduledHandle = Objects.requireNonNull(
                    scheduler.schedule(
                            () -> probeNow(epoch),
                            policy.pollInterval()
                    ),
                    "backend readiness scheduler returned null poll handle"
            );
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FAILED,
                    exception
            );
            return;
        }

        synchronized (lock) {
            if (epoch != lifecycleEpoch
                    || state != waitingState) {
                scheduledHandle.cancel();
                return;
            }
            pollHandle = scheduledHandle;
        }
    }

    private void completeReady(long epoch) {
        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return;
            }
            state = BackendReadinessLifecycleState.READY;
            ++lifecycleEpoch;
            cancelHandlesLocked();
            completion.complete(BackendReadinessLifecycleState.READY);
        }
    }

    private void timeoutReached(long epoch) {
        transitionWithCleanup(
                epoch,
                BackendReadinessLifecycleState.TIMED_OUT,
                null
        );
    }

    private void ownershipTerminated(
            long epoch,
            BackendBootstrapOwnershipState terminalState,
            Throwable failure
    ) {
        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return;
            }
        }

        if (failure != null || terminalState == null) {
            transitionWithoutCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FENCED
            );
            return;
        }

        switch (terminalState) {
            case FENCED -> transitionWithoutCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FENCED
            );
            case STOPPED -> transitionWithoutCleanup(
                    epoch,
                    BackendReadinessLifecycleState.CANCELLED
            );
            default -> transitionWithoutCleanup(
                    epoch,
                    BackendReadinessLifecycleState.FENCED
            );
        }
    }

    private void transitionWithCleanup(
            long expectedEpoch,
            BackendReadinessLifecycleState terminalState,
            Throwable primaryFailure
    ) {
        synchronized (lock) {
            if (expectedEpoch != lifecycleEpoch
                    || !canTransitionToTerminal(state)) {
                return;
            }
            state = terminalState;
            ++lifecycleEpoch;
            cancelHandlesLocked();
        }

        final CompletionStage<?> stopStage;
        try {
            stopStage = Objects.requireNonNull(
                    ownershipLifecycle.stop(),
                    "ownership lifecycle returned null stop stage"
            );
        } catch (RuntimeException exception) {
            completeCleanupFailure(primaryFailure, exception);
            return;
        }

        stopStage.whenComplete((result, cleanupFailure) -> {
            if (cleanupFailure != null) {
                completeCleanupFailure(primaryFailure, cleanupFailure);
                return;
            }
            if (result == null) {
                completeCleanupFailure(
                        primaryFailure,
                        new IllegalStateException(
                                "ownership lifecycle stop completed with null result"
                        )
                );
                return;
            }
            if (primaryFailure != null) {
                completion.completeExceptionally(primaryFailure);
            } else {
                completion.complete(terminalState);
            }
        });
    }

    private void transitionWithoutCleanup(
            long expectedEpoch,
            BackendReadinessLifecycleState terminalState
    ) {
        synchronized (lock) {
            if (expectedEpoch != lifecycleEpoch
                    || !canTransitionToTerminal(state)) {
                return;
            }
            state = terminalState;
            ++lifecycleEpoch;
            cancelHandlesLocked();
            completion.complete(terminalState);
        }
    }

    private void completeCleanupFailure(
            Throwable primaryFailure,
            Throwable cleanupFailure
    ) {
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(cleanupFailure);
            completion.completeExceptionally(primaryFailure);
        } else {
            completion.completeExceptionally(cleanupFailure);
        }
    }

    private void cancelHandlesLocked() {
        if (timeoutHandle != null) {
            timeoutHandle.cancel();
            timeoutHandle = null;
        }
        if (pollHandle != null) {
            pollHandle.cancel();
            pollHandle = null;
        }
    }

    private static boolean canTransitionToTerminal(
            BackendReadinessLifecycleState currentState
    ) {
        return currentState == BackendReadinessLifecycleState.NEW
                || isActiveState(currentState);
    }

    private static boolean isActiveState(
            BackendReadinessLifecycleState currentState
    ) {
        return currentState == BackendReadinessLifecycleState.WAITING_CONTROL
                || currentState == BackendReadinessLifecycleState.WAITING_HEALTH;
    }

    private static boolean isTerminalState(
            BackendReadinessLifecycleState currentState
    ) {
        return currentState == BackendReadinessLifecycleState.FAILED
                || currentState == BackendReadinessLifecycleState.TIMED_OUT
                || currentState == BackendReadinessLifecycleState.FENCED
                || currentState == BackendReadinessLifecycleState.CANCELLED;
    }
}

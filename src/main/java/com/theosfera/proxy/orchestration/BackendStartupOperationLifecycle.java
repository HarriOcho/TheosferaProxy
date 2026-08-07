package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipLifecycle;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipState;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Coordinates the provider-acceptance phase for one already-owned distributed
 * backend bootstrap operation.
 *
 * <p>This lifecycle never treats provider acceptance as backend readiness.
 * {@link BackendStartupOperationState#START_ACCEPTED} is the hand-off point to
 * the later Control Channel + health readiness phase.</p>
 */
public final class BackendStartupOperationLifecycle {

    private final BackendBootstrapOwnershipLifecycle ownershipLifecycle;
    private final BackendOrchestrationProvider orchestrationProvider;
    private final BackendStartupScheduler scheduler;
    private final Clock clock;
    private final BackendStartupPolicy policy;
    private final Object lock = new Object();
    private final CompletableFuture<BackendStartupOperationState> completion =
            new CompletableFuture<>();

    private BackendStartupOperationState state =
            BackendStartupOperationState.NEW;
    private long lifecycleEpoch;
    private long deadlineMillis;
    private int unavailableCount;
    private BackendBootstrapLease bootstrapLease;
    private BackendStartupScheduler.Handle timeoutHandle;
    private BackendStartupScheduler.Handle retryHandle;

    public BackendStartupOperationLifecycle(
            BackendBootstrapOwnershipLifecycle ownershipLifecycle,
            BackendOrchestrationProvider orchestrationProvider,
            BackendStartupScheduler scheduler,
            Clock clock,
            BackendStartupPolicy policy
    ) {
        this.ownershipLifecycle = Objects.requireNonNull(
                ownershipLifecycle,
                "ownershipLifecycle cannot be null"
        );
        this.orchestrationProvider = Objects.requireNonNull(
                orchestrationProvider,
                "orchestrationProvider cannot be null"
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

    public CompletionStage<BackendStartupOperationState> start() {
        final long epoch;

        synchronized (lock) {
            if (state != BackendStartupOperationState.NEW) {
                throw new IllegalStateException(
                        "backend startup operation lifecycle is single-use"
                );
            }

            state = BackendStartupOperationState.STARTING;
            epoch = ++lifecycleEpoch;
            deadlineMillis = Math.addExact(
                    clock.millis(),
                    policy.timeout().toMillis()
            );
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
                    BackendStartupOperationState.FAILED,
                    exception
            );
            return completion();
        }

        ownershipTermination.whenComplete(
                (terminalState, failure) ->
                        ownershipTerminated(
                                epoch,
                                terminalState,
                                failure
                        )
        );

        final BackendBootstrapLease currentLease;
        try {
            if (!ownershipLifecycle.hasAuthority()) {
                transitionWithoutCleanup(
                        epoch,
                        BackendStartupOperationState.FENCED
                );
                return completion();
            }
            currentLease = ownershipLifecycle.currentLease();
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FAILED,
                    exception
            );
            return completion();
        }

        if (currentLease == null) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FENCED,
                    null
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

        issueAttempt(epoch);
        return completion();
    }

    public CompletionStage<BackendStartupOperationState> cancel() {
        final long epoch;

        synchronized (lock) {
            if (state == BackendStartupOperationState.START_ACCEPTED
                    || isTerminalState(state)) {
                return completion();
            }
            epoch = lifecycleEpoch;
        }

        transitionWithCleanup(
                epoch,
                BackendStartupOperationState.CANCELLED,
                null
        );
        return completion();
    }

    public BackendStartupOperationState state() {
        synchronized (lock) {
            return state;
        }
    }

    public CompletionStage<BackendStartupOperationState> completion() {
        return completion.thenApply(value -> value);
    }

    private boolean scheduleTimeout(long epoch) {
        final BackendStartupScheduler.Handle scheduledHandle;
        try {
            scheduledHandle = Objects.requireNonNull(
                    scheduler.schedule(
                            () -> timeoutReached(epoch),
                            policy.timeout()
                    ),
                    "backend startup scheduler returned null timeout handle"
            );
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FAILED,
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

    private void issueAttempt(long epoch) {
        final BackendBootstrapLease expectedLease;
        boolean timedOut = false;

        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return;
            }

            retryHandle = null;
            if (clock.millis() >= deadlineMillis) {
                timedOut = true;
                expectedLease = null;
            } else {
                state = BackendStartupOperationState.STARTING;
                expectedLease = bootstrapLease;
            }
        }

        if (timedOut) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.TIMED_OUT,
                    null
            );
            return;
        }

        if (expectedLease == null) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FENCED,
                    null
            );
            return;
        }

        try {
            if (!ownershipLifecycle.hasAuthority()
                    || !expectedLease.equals(
                            ownershipLifecycle.currentLease()
                    )) {
                transitionWithCleanup(
                        epoch,
                        BackendStartupOperationState.FENCED,
                        null
                );
                return;
            }
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FAILED,
                    exception
            );
            return;
        }

        final CompletionStage<BackendStartResult> providerStage;
        try {
            providerStage = Objects.requireNonNull(
                    orchestrationProvider.requestStart(
                            new BackendStartRequest(expectedLease)
                    ),
                    "orchestration provider returned null stage"
            );
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FAILED,
                    exception
            );
            return;
        }

        providerStage.whenComplete(
                (result, failure) ->
                        providerCompleted(epoch, result, failure)
        );
    }

    private void providerCompleted(
            long epoch,
            BackendStartResult result,
            Throwable failure
    ) {
        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return;
            }
        }

        if (failure != null) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FAILED,
                    failure
            );
            return;
        }
        if (result == null) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FAILED,
                    new IllegalStateException(
                            "orchestration provider completed with null result"
                    )
            );
            return;
        }
        if (clock.millis() >= deadlineMillis) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.TIMED_OUT,
                    null
            );
            return;
        }

        switch (result.status()) {
            case ACCEPTED -> completeAccepted(epoch);
            case PROVIDER_UNAVAILABLE -> scheduleRetry(epoch);
            case STALE_AUTHORITY -> transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FENCED,
                    null
            );
            case CONFLICT, TARGET_NOT_FOUND, REJECTED ->
                    transitionWithCleanup(
                            epoch,
                            BackendStartupOperationState.FAILED,
                            null
                    );
        }
    }

    private void scheduleRetry(long epoch) {
        final Duration delay;
        final long remainingMillis;

        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return;
            }

            long now = clock.millis();
            if (now >= deadlineMillis) {
                remainingMillis = 0L;
                delay = null;
            } else {
                unavailableCount++;
                delay = policy.retryDelay(unavailableCount);
                remainingMillis = deadlineMillis - now;
                state = BackendStartupOperationState.RETRY_WAIT;
            }
        }

        if (remainingMillis <= 0L) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.TIMED_OUT,
                    null
            );
            return;
        }

        if (delay.toMillis() >= remainingMillis) {
            return;
        }

        final BackendStartupScheduler.Handle scheduledHandle;
        try {
            scheduledHandle = Objects.requireNonNull(
                    scheduler.schedule(
                            () -> issueAttempt(epoch),
                            delay
                    ),
                    "backend startup scheduler returned null retry handle"
            );
        } catch (RuntimeException exception) {
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FAILED,
                    exception
            );
            return;
        }

        synchronized (lock) {
            if (epoch != lifecycleEpoch
                    || state != BackendStartupOperationState.RETRY_WAIT) {
                scheduledHandle.cancel();
                return;
            }
            retryHandle = scheduledHandle;
        }
    }

    private void completeAccepted(long epoch) {
        synchronized (lock) {
            if (epoch != lifecycleEpoch || !isActiveState(state)) {
                return;
            }

            state = BackendStartupOperationState.START_ACCEPTED;
            ++lifecycleEpoch;
            cancelHandlesLocked();
            completion.complete(
                    BackendStartupOperationState.START_ACCEPTED
            );
        }
    }

    private void timeoutReached(long epoch) {
        transitionWithCleanup(
                epoch,
                BackendStartupOperationState.TIMED_OUT,
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
            transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FENCED,
                    failure
            );
            return;
        }

        switch (terminalState) {
            case FENCED -> transitionWithoutCleanup(
                    epoch,
                    BackendStartupOperationState.FENCED
            );
            case STOPPED -> transitionWithoutCleanup(
                    epoch,
                    BackendStartupOperationState.CANCELLED
            );
            default -> transitionWithCleanup(
                    epoch,
                    BackendStartupOperationState.FENCED,
                    new IllegalStateException(
                            "ownership termination completed with non-terminal state: "
                                    + terminalState
                    )
            );
        }
    }

    private void transitionWithCleanup(
            long expectedEpoch,
            BackendStartupOperationState terminalState,
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
            completeCleanupFailure(
                    primaryFailure,
                    exception
            );
            return;
        }

        stopStage.whenComplete((result, cleanupFailure) -> {
            if (cleanupFailure != null) {
                completeCleanupFailure(
                        primaryFailure,
                        cleanupFailure
                );
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
            BackendStartupOperationState terminalState
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
        if (retryHandle != null) {
            retryHandle.cancel();
            retryHandle = null;
        }
    }

    private static boolean canTransitionToTerminal(
            BackendStartupOperationState currentState
    ) {
        return currentState == BackendStartupOperationState.NEW
                || isActiveState(currentState);
    }

    private static boolean isActiveState(
            BackendStartupOperationState currentState
    ) {
        return currentState == BackendStartupOperationState.STARTING
                || currentState == BackendStartupOperationState.RETRY_WAIT;
    }

    private static boolean isTerminalState(
            BackendStartupOperationState currentState
    ) {
        return currentState == BackendStartupOperationState.FAILED
                || currentState == BackendStartupOperationState.TIMED_OUT
                || currentState == BackendStartupOperationState.FENCED
                || currentState == BackendStartupOperationState.CANCELLED;
    }
}

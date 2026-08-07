package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapAcquireResult;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipLifecycle;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipLifecycleFactory;
import com.theosfera.proxy.coordination.BackendBootstrapReleaseResult;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Provider-neutral cold-start pipeline from distributed bootstrap ownership to
 * authoritative control-channel readiness.
 *
 * <p>Capacity is deliberately outside this coordinator. A READY result means
 * bootstrap ownership was released exactly after readiness was proven; the
 * caller must re-resolve the target and only then reserve distributed capacity.</p>
 */
public final class BackendColdStartCoordinator {

    private final BackendBootstrapOwnershipLifecycleFactory ownershipFactory;
    private final BackendOrchestrationProvider orchestrationProvider;
    private final BackendStartupScheduler startupScheduler;
    private final Clock clock;
    private final BackendStartupPolicy startupPolicy;
    private final BackendReadinessProbe readinessProbe;
    private final BackendReadinessScheduler readinessScheduler;
    private final BackendReadinessPolicy readinessPolicy;

    public BackendColdStartCoordinator(
            BackendBootstrapOwnershipLifecycleFactory ownershipFactory,
            BackendOrchestrationProvider orchestrationProvider,
            BackendStartupScheduler startupScheduler,
            Clock clock,
            BackendStartupPolicy startupPolicy,
            BackendReadinessProbe readinessProbe,
            BackendReadinessScheduler readinessScheduler,
            BackendReadinessPolicy readinessPolicy
    ) {
        this.ownershipFactory = Objects.requireNonNull(
                ownershipFactory,
                "ownershipFactory cannot be null"
        );
        this.orchestrationProvider = Objects.requireNonNull(
                orchestrationProvider,
                "orchestrationProvider cannot be null"
        );
        this.startupScheduler = Objects.requireNonNull(
                startupScheduler,
                "startupScheduler cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.startupPolicy = Objects.requireNonNull(
                startupPolicy,
                "startupPolicy cannot be null"
        );
        this.readinessProbe = Objects.requireNonNull(
                readinessProbe,
                "readinessProbe cannot be null"
        );
        this.readinessScheduler = Objects.requireNonNull(
                readinessScheduler,
                "readinessScheduler cannot be null"
        );
        this.readinessPolicy = Objects.requireNonNull(
                readinessPolicy,
                "readinessPolicy cannot be null"
        );
    }

    public CompletionStage<BackendColdStartResult> start(
            String targetBackendName,
            UUID requestId,
            UUID playerId
    ) {
        String target = requireBackendName(targetBackendName);
        UUID nonNullRequestId = Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );
        UUID nonNullPlayerId = Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );
        CompletableFuture<BackendColdStartResult> completion =
                new CompletableFuture<>();

        final BackendBootstrapOwnershipLifecycleFactory.StartedOwnership started;
        try {
            started = Objects.requireNonNull(
                    ownershipFactory.start(
                            target,
                            nonNullRequestId,
                            nonNullPlayerId
                    ),
                    "ownership factory returned null operation"
            );
        } catch (RuntimeException exception) {
            completion.complete(
                    BackendColdStartResult.of(
                            BackendColdStartResult.Status
                                    .COORDINATION_UNAVAILABLE,
                            target
                    )
            );
            return completion;
        }

        started.acquisition().whenComplete((result, failure) -> {
            if (failure != null || result == null) {
                completion.complete(
                        BackendColdStartResult.of(
                                BackendColdStartResult.Status.FAILED,
                                target
                        )
                );
                return;
            }
            handleAcquire(
                    target,
                    started.lifecycle(),
                    result,
                    completion
            );
        });

        return completion;
    }

    private void handleAcquire(
            String target,
            BackendBootstrapOwnershipLifecycle ownership,
            BackendBootstrapAcquireResult acquireResult,
            CompletableFuture<BackendColdStartResult> completion
    ) {
        switch (acquireResult.status()) {
            case ACQUIRED, ALREADY_OWNED -> launchStartup(
                    target,
                    ownership,
                    completion
            );
            case TARGET_BUSY -> complete(
                    completion,
                    target,
                    BackendColdStartResult.Status.TARGET_BUSY
            );
            case REQUEST_ID_CONFLICT -> complete(
                    completion,
                    target,
                    BackendColdStartResult.Status.REQUEST_ID_CONFLICT
            );
            case COORDINATION_UNAVAILABLE -> complete(
                    completion,
                    target,
                    BackendColdStartResult.Status.COORDINATION_UNAVAILABLE
            );
            case MEMBERSHIP_NOT_FOUND, NOT_MEMBERSHIP_OWNER -> complete(
                    completion,
                    target,
                    BackendColdStartResult.Status.FENCED
            );
        }
    }

    private void launchStartup(
            String target,
            BackendBootstrapOwnershipLifecycle ownership,
            CompletableFuture<BackendColdStartResult> completion
    ) {
        BackendStartupOperationLifecycle startup =
                new BackendStartupOperationLifecycle(
                        ownership,
                        orchestrationProvider,
                        startupScheduler,
                        clock,
                        startupPolicy
                );

        final CompletionStage<BackendStartupOperationState> stage;
        try {
            stage = Objects.requireNonNull(
                    startup.start(),
                    "startup lifecycle returned null stage"
            );
        } catch (RuntimeException exception) {
            complete(
                    completion,
                    target,
                    BackendColdStartResult.Status.FAILED
            );
            return;
        }

        stage.whenComplete((state, failure) -> {
            if (failure != null || state == null) {
                complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FAILED
                );
                return;
            }

            switch (state) {
                case START_ACCEPTED -> launchReadiness(
                        target,
                        ownership,
                        completion
                );
                case TIMED_OUT -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.START_TIMED_OUT
                );
                case FENCED -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FENCED
                );
                case FAILED, CANCELLED -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FAILED
                );
                case NEW, STARTING, RETRY_WAIT -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FAILED
                );
            }
        });
    }

    private void launchReadiness(
            String target,
            BackendBootstrapOwnershipLifecycle ownership,
            CompletableFuture<BackendColdStartResult> completion
    ) {
        BackendReadinessLifecycle readiness = new BackendReadinessLifecycle(
                ownership,
                readinessProbe,
                readinessScheduler,
                readinessPolicy
        );

        final CompletionStage<BackendReadinessLifecycleState> stage;
        try {
            stage = Objects.requireNonNull(
                    readiness.start(),
                    "readiness lifecycle returned null stage"
            );
        } catch (RuntimeException exception) {
            complete(
                    completion,
                    target,
                    BackendColdStartResult.Status.FAILED
            );
            return;
        }

        stage.whenComplete((state, failure) -> {
            if (failure != null || state == null) {
                complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FAILED
                );
                return;
            }

            switch (state) {
                case READY -> releaseReadyOwnership(
                        target,
                        ownership,
                        completion
                );
                case TIMED_OUT -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.READINESS_TIMED_OUT
                );
                case FENCED -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FENCED
                );
                case FAILED, CANCELLED -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FAILED
                );
                case NEW, WAITING_CONTROL, WAITING_HEALTH -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FAILED
                );
            }
        });
    }

    private void releaseReadyOwnership(
            String target,
            BackendBootstrapOwnershipLifecycle ownership,
            CompletableFuture<BackendColdStartResult> completion
    ) {
        final CompletionStage<Optional<BackendBootstrapReleaseResult>> stage;
        try {
            stage = Objects.requireNonNull(
                    ownership.stop(),
                    "ownership lifecycle returned null stop stage"
            );
        } catch (RuntimeException exception) {
            complete(
                    completion,
                    target,
                    BackendColdStartResult.Status.FAILED
            );
            return;
        }

        stage.whenComplete((optionalResult, failure) -> {
            if (failure != null || optionalResult == null
                    || optionalResult.isEmpty()) {
                complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FAILED
                );
                return;
            }

            BackendBootstrapReleaseResult release =
                    optionalResult.orElseThrow();
            switch (release.status()) {
                case RELEASED -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.READY
                );
                case COORDINATION_UNAVAILABLE -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.COORDINATION_UNAVAILABLE
                );
                case NOT_FOUND,
                        NOT_OWNER,
                        CONFLICT,
                        MEMBERSHIP_NOT_FOUND,
                        NOT_MEMBERSHIP_OWNER -> complete(
                        completion,
                        target,
                        BackendColdStartResult.Status.FENCED
                );
            }
        });
    }

    private static void complete(
            CompletableFuture<BackendColdStartResult> completion,
            String target,
            BackendColdStartResult.Status status
    ) {
        completion.complete(BackendColdStartResult.of(status, target));
    }

    private static String requireBackendName(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "targetBackendName cannot be null"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "targetBackendName cannot be blank"
            );
        }
        return normalized;
    }
}

package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapLease;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class FencedBackendOrchestrationProvider
        implements BackendOrchestrationProvider {

    private final BackendStartTargetResolver targetResolver;
    private final BackendStartActuator actuator;

    public FencedBackendOrchestrationProvider(
            BackendStartTargetResolver targetResolver,
            BackendStartActuator actuator
    ) {
        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
        );
        this.actuator = Objects.requireNonNull(
                actuator,
                "actuator cannot be null"
        );
    }

    @Override
    public CompletionStage<BackendStartResult> requestStart(
            BackendStartRequest request
    ) {
        BackendStartRequest nonNullRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        BackendBootstrapLease bootstrapLease =
                nonNullRequest.bootstrapLease();

        Optional<BackendStartTarget> resolvedTarget =
                Objects.requireNonNull(
                        targetResolver.resolve(
                                bootstrapLease.targetBackendName()
                        ),
                        "targetResolver returned null"
                );

        if (resolvedTarget.isEmpty()) {
            return CompletableFuture.completedFuture(
                    BackendStartResult.of(
                            BackendStartResult.Status.TARGET_NOT_FOUND
                    )
            );
        }

        BackendStartActuationRequest actuationRequest =
                new BackendStartActuationRequest(
                        resolvedTarget.orElseThrow(),
                        bootstrapLease
                );

        CompletionStage<BackendStartActuationResult> actuationStage =
                Objects.requireNonNull(
                        actuator.startIfCurrent(actuationRequest),
                        "actuator returned null stage"
                );

        return actuationStage.thenApply(result -> mapResult(
                Objects.requireNonNull(
                        result,
                        "actuator completed with null result"
                )
        ));
    }

    private static BackendStartResult mapResult(
            BackendStartActuationResult result
    ) {
        BackendStartResult.Status status = switch (result.status()) {
            case ACCEPTED -> BackendStartResult.Status.ACCEPTED;
            case STALE_AUTHORITY ->
                    BackendStartResult.Status.STALE_AUTHORITY;
            case CONFLICT -> BackendStartResult.Status.CONFLICT;
            case ACTUATOR_UNAVAILABLE ->
                    BackendStartResult.Status.PROVIDER_UNAVAILABLE;
            case REJECTED -> BackendStartResult.Status.REJECTED;
        };

        return BackendStartResult.of(status);
    }
}

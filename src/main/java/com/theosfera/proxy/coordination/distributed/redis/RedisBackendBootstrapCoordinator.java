package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendBootstrapAcquireRequest;
import com.theosfera.proxy.coordination.BackendBootstrapAcquireResult;
import com.theosfera.proxy.coordination.BackendBootstrapCoordinator;
import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.BackendBootstrapReleaseResult;
import com.theosfera.proxy.coordination.BackendBootstrapRenewResult;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class RedisBackendBootstrapCoordinator
        implements BackendBootstrapCoordinator {

    private final RedisBackendBootstrapStore store;
    private final Duration bootstrapTtl;

    public RedisBackendBootstrapCoordinator(
            RedisScriptingAsyncCommands<String, String> commands,
            Duration bootstrapTtl
    ) {
        this(
                new LettuceRedisBackendBootstrapStore(
                        commands,
                        RedisBackendBootstrapKeyspace.defaultKeyspace(),
                        RedisProxyMembershipKeyspace.defaultKeyspace()
                ),
                bootstrapTtl
        );
    }

    RedisBackendBootstrapCoordinator(
            RedisBackendBootstrapStore store,
            Duration bootstrapTtl
    ) {
        this.store = Objects.requireNonNull(
                store,
                "store cannot be null"
        );
        this.bootstrapTtl = requirePositiveTtl(bootstrapTtl);
    }

    @Override
    public CompletionStage<BackendBootstrapAcquireResult> acquire(
            BackendBootstrapAcquireRequest request
    ) {
        BackendBootstrapAcquireRequest nonNullRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );

        return store.acquire(nonNullRequest, bootstrapTtl)
                .handle((response, failure) -> {
                    if (failure != null) {
                        return failedOrUnavailableAcquire(failure);
                    }

                    try {
                        return completed(
                                mapAcquireResponse(
                                        nonNullRequest,
                                        response
                                )
                        );
                    } catch (RuntimeException exception) {
                        return RedisBackendBootstrapCoordinator
                                .<BackendBootstrapAcquireResult>failed(exception);
                    }
                })
                .thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<BackendBootstrapRenewResult> renew(
            BackendBootstrapLease expected
    ) {
        BackendBootstrapLease nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );

        return store.renew(nonNullExpected, bootstrapTtl)
                .handle((response, failure) -> {
                    if (failure != null) {
                        return failedOrUnavailableRenew(failure);
                    }

                    try {
                        return completed(
                                mapRenewResponse(
                                        nonNullExpected,
                                        response
                                )
                        );
                    } catch (RuntimeException exception) {
                        return RedisBackendBootstrapCoordinator
                                .<BackendBootstrapRenewResult>failed(exception);
                    }
                })
                .thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<BackendBootstrapReleaseResult> releaseIfOwned(
            BackendBootstrapLease expected
    ) {
        BackendBootstrapLease nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );

        return store.releaseIfOwned(nonNullExpected)
                .handle((response, failure) -> {
                    if (failure != null) {
                        return failedOrUnavailableRelease(failure);
                    }

                    try {
                        return completed(mapReleaseResponse(response));
                    } catch (RuntimeException exception) {
                        return RedisBackendBootstrapCoordinator
                                .<BackendBootstrapReleaseResult>failed(exception);
                    }
                })
                .thenCompose(stage -> stage);
    }

    private BackendBootstrapAcquireResult mapAcquireResponse(
            BackendBootstrapAcquireRequest request,
            RedisBackendBootstrapAcquireResponse response
    ) {
        Objects.requireNonNull(response, "response cannot be null");

        return switch (response.status()) {
            case ACQUIRED -> BackendBootstrapAcquireResult.withLease(
                    BackendBootstrapAcquireResult.Status.ACQUIRED,
                    requireAcquireLease(request, response)
            );
            case ALREADY_OWNED -> BackendBootstrapAcquireResult.withLease(
                    BackendBootstrapAcquireResult.Status.ALREADY_OWNED,
                    requireAcquireLease(request, response)
            );
            case TARGET_BUSY -> acquireWithoutLease(
                    BackendBootstrapAcquireResult.Status.TARGET_BUSY
            );
            case REQUEST_ID_CONFLICT -> acquireWithoutLease(
                    BackendBootstrapAcquireResult.Status.REQUEST_ID_CONFLICT
            );
            case MEMBERSHIP_NOT_FOUND -> acquireWithoutLease(
                    BackendBootstrapAcquireResult.Status.MEMBERSHIP_NOT_FOUND
            );
            case NOT_MEMBERSHIP_OWNER -> acquireWithoutLease(
                    BackendBootstrapAcquireResult.Status.NOT_MEMBERSHIP_OWNER
            );
            case CORRUPT -> throw invalidState(
                    "Redis backend bootstrap acquire state is corrupt"
            );
        };
    }

    private BackendBootstrapRenewResult mapRenewResponse(
            BackendBootstrapLease expected,
            RedisBackendBootstrapRenewResponse response
    ) {
        Objects.requireNonNull(response, "response cannot be null");

        return switch (response.status()) {
            case RENEWED -> {
                BackendBootstrapLease lease = response.lease().orElseThrow(
                        () -> invalidState(
                                "Redis backend bootstrap renew omitted lease"
                        )
                );

                if (!lease.equals(expected)) {
                    throw invalidState(
                            "Redis backend bootstrap renew returned a mismatched lease"
                    );
                }

                yield BackendBootstrapRenewResult.renewed(lease);
            }
            case NOT_FOUND -> renewWithoutLease(
                    BackendBootstrapRenewResult.Status.NOT_FOUND
            );
            case NOT_OWNER -> renewWithoutLease(
                    BackendBootstrapRenewResult.Status.NOT_OWNER
            );
            case CONFLICT -> renewWithoutLease(
                    BackendBootstrapRenewResult.Status.CONFLICT
            );
            case MEMBERSHIP_NOT_FOUND -> renewWithoutLease(
                    BackendBootstrapRenewResult.Status.MEMBERSHIP_NOT_FOUND
            );
            case NOT_MEMBERSHIP_OWNER -> renewWithoutLease(
                    BackendBootstrapRenewResult.Status.NOT_MEMBERSHIP_OWNER
            );
            case CORRUPT -> throw invalidState(
                    "Redis backend bootstrap renew state is corrupt"
            );
        };
    }

    private BackendBootstrapReleaseResult mapReleaseResponse(
            RedisBackendBootstrapReleaseResponse response
    ) {
        Objects.requireNonNull(response, "response cannot be null");

        BackendBootstrapReleaseResult.Status status = switch (response.status()) {
            case RELEASED -> BackendBootstrapReleaseResult.Status.RELEASED;
            case NOT_FOUND -> BackendBootstrapReleaseResult.Status.NOT_FOUND;
            case NOT_OWNER -> BackendBootstrapReleaseResult.Status.NOT_OWNER;
            case CONFLICT -> BackendBootstrapReleaseResult.Status.CONFLICT;
            case MEMBERSHIP_NOT_FOUND ->
                    BackendBootstrapReleaseResult.Status.MEMBERSHIP_NOT_FOUND;
            case NOT_MEMBERSHIP_OWNER ->
                    BackendBootstrapReleaseResult.Status.NOT_MEMBERSHIP_OWNER;
            case CORRUPT -> throw invalidState(
                    "Redis backend bootstrap release state is corrupt"
            );
        };

        return new BackendBootstrapReleaseResult(status);
    }

    private BackendBootstrapLease requireAcquireLease(
            BackendBootstrapAcquireRequest request,
            RedisBackendBootstrapAcquireResponse response
    ) {
        BackendBootstrapLease lease = response.lease().orElseThrow(
                () -> invalidState(
                        "Redis backend bootstrap acquire omitted lease"
                )
        );

        if (!lease.targetBackendName().equals(request.targetBackendName())
                || !lease.requestId().equals(request.requestId())
                || !lease.playerId().equals(request.playerId())
                || !lease.ownerMembership().equals(request.membershipLease())) {
            throw invalidState(
                    "Redis backend bootstrap acquire returned a mismatched lease"
            );
        }

        return lease;
    }

    private CompletionStage<BackendBootstrapAcquireResult>
    failedOrUnavailableAcquire(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RedisBackendBootstrapInvalidStateException) {
            return failed(cause);
        }
        return completed(
                acquireWithoutLease(
                        BackendBootstrapAcquireResult.Status
                                .COORDINATION_UNAVAILABLE
                )
        );
    }

    private CompletionStage<BackendBootstrapRenewResult>
    failedOrUnavailableRenew(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RedisBackendBootstrapInvalidStateException) {
            return failed(cause);
        }
        return completed(
                renewWithoutLease(
                        BackendBootstrapRenewResult.Status
                                .COORDINATION_UNAVAILABLE
                )
        );
    }

    private CompletionStage<BackendBootstrapReleaseResult>
    failedOrUnavailableRelease(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RedisBackendBootstrapInvalidStateException) {
            return failed(cause);
        }
        return completed(
                new BackendBootstrapReleaseResult(
                        BackendBootstrapReleaseResult.Status
                                .COORDINATION_UNAVAILABLE
                )
        );
    }

    private static BackendBootstrapAcquireResult acquireWithoutLease(
            BackendBootstrapAcquireResult.Status status
    ) {
        return BackendBootstrapAcquireResult.withoutLease(status);
    }

    private static BackendBootstrapRenewResult renewWithoutLease(
            BackendBootstrapRenewResult.Status status
    ) {
        return BackendBootstrapRenewResult.withoutLease(status);
    }

    private static RedisBackendBootstrapInvalidStateException invalidState(
            String message
    ) {
        return new RedisBackendBootstrapInvalidStateException(message);
    }

    private static Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(
                ttl,
                "bootstrapTtl cannot be null"
        );
        if (nonNullTtl.isZero()
                || nonNullTtl.isNegative()
                || nonNullTtl.toMillis() <= 0L) {
            throw new IllegalArgumentException(
                    "bootstrapTtl must be positive and at least one millisecond"
            );
        }
        return nonNullTtl;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }
}

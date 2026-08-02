package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.PlayerSessionRenewResult;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerSessionRegistrationResult;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RedisPlayerSessionCoordinator
        implements PlayerSessionCoordinator {

    private final RedisPlayerSessionStore store;
    private final AuthenticatedPlayerSessionRegistry
            sessionRegistry;
    private final Duration sessionTtl;
    private final Object localOwnershipLock = new Object();
    private final Map<UUID, PlayerSessionLease> localLeases =
            new HashMap<>();

    public RedisPlayerSessionCoordinator(
            RedisScriptingAsyncCommands<String, String> commands,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            Duration sessionTtl
    ) {
        this(
                commands,
                sessionRegistry,
                sessionTtl,
                RedisPlayerSessionKeyspace.defaultKeyspace()
        );
    }

    public RedisPlayerSessionCoordinator(
            RedisScriptingAsyncCommands<String, String> commands,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            Duration sessionTtl,
            RedisPlayerSessionKeyspace keyspace
    ) {
        this(
                new LettuceRedisPlayerSessionStore(
                        commands,
                        keyspace
                ),
                sessionRegistry,
                sessionTtl
        );
    }

    RedisPlayerSessionCoordinator(
            RedisPlayerSessionStore store,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            Duration sessionTtl
    ) {
        this.store = Objects.requireNonNull(
                store,
                "store cannot be null"
        );
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
        this.sessionTtl = requirePositiveTtl(sessionTtl);
    }

    @Override
    public CompletionStage<PlayerSessionAcquireResult> acquire(
            PlayerSessionLeaseRequest request
    ) {
        PlayerSessionLeaseRequest nonNullRequest =
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                );

        return store.acquire(
                nonNullRequest,
                sessionTtl
        ).handle(
                (response, failure) -> acquireAfterStore(
                        nonNullRequest,
                        response,
                        failure
                )
        ).thenCompose(
                stage -> stage
        );
    }

    @Override
    public CompletionStage<PlayerSessionRenewResult> renew(
            PlayerSessionLease expected
    ) {
        PlayerSessionLease nonNullExpected =
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                );

        return store.renew(
                nonNullExpected,
                sessionTtl
        ).handle(
                (response, failure) -> renewAfterStore(
                        nonNullExpected,
                        response,
                        failure
                )
        ).thenCompose(
                stage -> stage
        );
    }

    @Override
    public CompletionStage<Boolean> releaseIfOwned(
            PlayerSessionLease expected
    ) {
        PlayerSessionLease nonNullExpected =
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                );

        return store.releaseIfOwned(nonNullExpected)
                .thenApply(
                        released -> {
                            if (Boolean.TRUE.equals(released)) {
                                removeLocalIfExact(nonNullExpected);
                                return true;
                            }

                            return false;
                        }
                );
    }

    Optional<PlayerSessionLease> localLeaseFor(UUID playerId) {
        synchronized (localOwnershipLock) {
            return Optional.ofNullable(
                    localLeases.getOrDefault(
                            Objects.requireNonNull(
                                    playerId,
                                    "playerId cannot be null"
                            ),
                            null
                    )
            );
        }
    }

    private CompletionStage<PlayerSessionAcquireResult>
    acquireAfterStore(
            PlayerSessionLeaseRequest request,
            RedisPlayerSessionAcquireResponse response,
            Throwable failure
    ) {
        if (failure != null) {
            return failedOrUnavailableAcquire(failure);
        }

        return handleAcquireResponse(request, response);
    }

    private CompletionStage<PlayerSessionRenewResult>
    renewAfterStore(
            PlayerSessionLease expected,
            RedisPlayerSessionRenewResponse response,
            Throwable failure
    ) {
        if (failure != null) {
            return failedOrUnavailableRenew(failure);
        }

        try {
            return completed(handleRenewResponse(expected, response));
        } catch (RuntimeException exception) {
            return failedStage(exception);
        }
    }

    private CompletionStage<PlayerSessionAcquireResult>
    handleAcquireResponse(
            PlayerSessionLeaseRequest request,
            RedisPlayerSessionAcquireResponse response
    ) {
        return switch (response.status()) {
            case ACQUIRED ->
                    reconcileNewlyAcquiredLease(
                            request,
                            requireAcquireLease(
                                    request,
                                    response
                            )
                    );
            case ALREADY_OWNED ->
                    reconcileAlreadyOwnedLease(
                            request,
                            requireAcquireLease(
                                    request,
                                    response
                            )
                    );
            case OWNED_BY_OTHER_PROXY ->
                    completed(
                            PlayerSessionAcquireResult
                                    .withoutLease(
                                            PlayerSessionAcquireResult
                                                    .Status
                                                    .OWNED_BY_OTHER_PROXY
                                    )
                    );
            case CONFLICT ->
                    completed(
                            PlayerSessionAcquireResult
                                    .withoutLease(
                                            PlayerSessionAcquireResult
                                                    .Status.CONFLICT
                                    )
                    );
            case CORRUPT ->
                    failedStage(
                            new RedisPlayerSessionInvalidStateException(
                                    "Redis player session lease state is corrupt"
                            )
                    );
        };
    }

    private CompletionStage<PlayerSessionAcquireResult>
    reconcileNewlyAcquiredLease(
            PlayerSessionLeaseRequest request,
            PlayerSessionLease lease
    ) {
        if (reconcileLocalLease(lease, true)
                == LocalLeaseReconciliation.APPLIED) {
            return completed(
                    PlayerSessionAcquireResult.acquired(lease)
            );
        }

        return store.releaseIfOwned(lease)
                .handle(
                        (released, failure) -> {
                            if (failure != null) {
                                return cleanupFailureAcquire(failure);
                            }

                            if (!Boolean.TRUE.equals(released)) {
                                return completed(
                                        PlayerSessionAcquireResult
                                                .withoutLease(
                                                        PlayerSessionAcquireResult
                                                                .Status
                                                                .COORDINATION_UNAVAILABLE
                                                )
                                );
                            }

                            return completed(
                                    PlayerSessionAcquireResult
                                            .withoutLease(
                                                    PlayerSessionAcquireResult
                                                            .Status.CONFLICT
                                            )
                                    );
                        }
                ).thenCompose(
                        stage -> stage
                );
    }

    private CompletionStage<PlayerSessionAcquireResult>
    reconcileAlreadyOwnedLease(
            PlayerSessionLeaseRequest request,
            PlayerSessionLease lease
    ) {
        if (reconcileLocalLease(lease, true)
                == LocalLeaseReconciliation.CONFLICT) {
            return completed(
                    PlayerSessionAcquireResult
                            .withoutLease(
                                    PlayerSessionAcquireResult.Status
                                            .COORDINATION_UNAVAILABLE
                            )
            );
        }

        return completed(
                PlayerSessionAcquireResult.alreadyOwned(lease)
        );
    }

    private PlayerSessionRenewResult handleRenewResponse(
            PlayerSessionLease expected,
            Object rawResponse
    ) {
        if (!(rawResponse
                instanceof RedisPlayerSessionRenewResponse response)) {
            throw new IllegalStateException(
                    "Unexpected Redis renew response"
            );
        }

        return switch (response.status()) {
            case RENEWED -> {
                PlayerSessionLease lease =
                        requireRenewLease(expected, response);

                if (!hasExactLocalLease(lease)) {
                    yield PlayerSessionRenewResult.withoutLease(
                            PlayerSessionRenewResult.Status.CONFLICT
                    );
                }

                yield PlayerSessionRenewResult.renewed(lease);
            }
            case NOT_FOUND ->
                    PlayerSessionRenewResult.withoutLease(
                            PlayerSessionRenewResult.Status.NOT_FOUND
                    );
            case NOT_OWNER ->
                    PlayerSessionRenewResult.withoutLease(
                            PlayerSessionRenewResult.Status.NOT_OWNER
                    );
            case CONFLICT ->
                    PlayerSessionRenewResult.withoutLease(
                            PlayerSessionRenewResult.Status.CONFLICT
                    );
            case CORRUPT ->
                    throw new RedisPlayerSessionInvalidStateException(
                            "Redis player session lease state is corrupt"
                    );
        };
    }

    private LocalLeaseReconciliation reconcileLocalLease(
            PlayerSessionLease lease,
            boolean allowSameSessionOwnerReplacement
    ) {
        synchronized (localOwnershipLock) {
            UUID playerId = lease.session().playerId();
            PlayerSessionLease current = localLeases.getOrDefault(
                    playerId,
                    null
            );

            if (current != null
                    && !current.equals(lease)
                    && (!allowSameSessionOwnerReplacement
                    || !sameSessionAndOwner(current, lease))) {
                return LocalLeaseReconciliation.CONFLICT;
            }

            PlayerSessionRegistrationResult registrationResult =
                    sessionRegistry.register(lease.session());

            if (registrationResult
                    == PlayerSessionRegistrationResult.CONFLICT) {
                return LocalLeaseReconciliation.CONFLICT;
            }

            localLeases.put(playerId, lease);
            return LocalLeaseReconciliation.APPLIED;
        }
    }

    private boolean hasExactLocalLease(PlayerSessionLease lease) {
        synchronized (localOwnershipLock) {
            return lease.equals(
                    localLeases.getOrDefault(
                            lease.session().playerId(),
                            null
                    )
            ) && sessionRegistry.find(
                    lease.session().playerId()
            ).filter(lease.session()::equals).isPresent();
        }
    }

    private void removeLocalIfExact(PlayerSessionLease expected) {
        synchronized (localOwnershipLock) {
            UUID playerId = expected.session().playerId();

            if (!expected.equals(
                    localLeases.getOrDefault(playerId, null)
            )) {
                return;
            }

            localLeases.remove(playerId);
            sessionRegistry.removeIfMatches(expected.session());
        }
    }

    private boolean sameSessionAndOwner(
            PlayerSessionLease current,
            PlayerSessionLease replacement
    ) {
        return current.session().equals(replacement.session())
                && current.owner().equals(replacement.owner());
    }

    private PlayerSessionLease requireAcquireLease(
            PlayerSessionLeaseRequest request,
            RedisPlayerSessionAcquireResponse response
    ) {
        PlayerSessionLease lease =
                response.lease().orElseThrow(
                        () -> new IllegalStateException(
                                "Redis acquire response omitted lease"
                        )
                );

        if (!lease.session().equals(request.session())
                || !lease.owner().equals(request.owner())) {
            throw new IllegalStateException(
                    "Redis acquire response returned "
                            + "a mismatched lease"
            );
        }

        return lease;
    }

    private PlayerSessionLease requireRenewLease(
            PlayerSessionLease expected,
            RedisPlayerSessionRenewResponse response
    ) {
        PlayerSessionLease lease =
                response.lease().orElseThrow(
                        () -> new IllegalStateException(
                                "Redis renew response omitted lease"
                        )
                );

        if (!lease.equals(expected)) {
            throw new IllegalStateException(
                    "Redis renew response returned "
                            + "a mismatched lease"
            );
        }

        return lease;
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(
                ttl,
                "sessionTtl cannot be null"
        );

        if (nonNullTtl.isZero()
                || nonNullTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "sessionTtl must be positive"
            );
        }

        return nonNullTtl;
    }

    private CompletionStage<PlayerSessionAcquireResult>
    failedOrUnavailableAcquire(Throwable failure) {
        if (RedisPlayerSessionFailures.isOperational(failure)) {
            return completed(
                    PlayerSessionAcquireResult.withoutLease(
                            PlayerSessionAcquireResult.Status
                                    .COORDINATION_UNAVAILABLE
                    )
            );
        }

        return failedStage(
                RedisPlayerSessionFailures.unwrap(failure)
        );
    }

    private CompletionStage<PlayerSessionRenewResult>
    failedOrUnavailableRenew(Throwable failure) {
        if (RedisPlayerSessionFailures.isOperational(failure)) {
            return completed(
                    PlayerSessionRenewResult.withoutLease(
                            PlayerSessionRenewResult.Status
                                    .COORDINATION_UNAVAILABLE
                    )
            );
        }

        return failedStage(
                RedisPlayerSessionFailures.unwrap(failure)
        );
    }

    private CompletionStage<PlayerSessionAcquireResult>
    cleanupFailureAcquire(Throwable failure) {
        if (RedisPlayerSessionFailures.isOperational(failure)) {
            return completed(
                    PlayerSessionAcquireResult.withoutLease(
                            PlayerSessionAcquireResult.Status
                                    .COORDINATION_UNAVAILABLE
                    )
            );
        }

        return failedStage(
                RedisPlayerSessionFailures.unwrap(failure)
        );
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failedStage(
            Throwable failure
    ) {
        return CompletableFuture.failedFuture(failure);
    }

    private enum LocalLeaseReconciliation {
        APPLIED,
        CONFLICT
    }
}

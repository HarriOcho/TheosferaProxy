package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipAcquireResult;
import com.theosfera.proxy.coordination.ProxyMembershipCoordinator;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import com.theosfera.proxy.coordination.ProxyMembershipRenewResult;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RedisProxyMembershipCoordinator
        implements ProxyMembershipCoordinator {

    private final RedisProxyMembershipStore store;
    private final Duration membershipTtl;

    public RedisProxyMembershipCoordinator(
            RedisScriptingAsyncCommands<String, String> commands,
            Duration membershipTtl
    ) {
        this(
                commands,
                membershipTtl,
                RedisProxyMembershipKeyspace.defaultKeyspace()
        );
    }

    public RedisProxyMembershipCoordinator(
            RedisScriptingAsyncCommands<String, String> commands,
            Duration membershipTtl,
            RedisProxyMembershipKeyspace keyspace
    ) {
        this(
                new LettuceRedisProxyMembershipStore(commands, keyspace),
                membershipTtl
        );
    }

    RedisProxyMembershipCoordinator(
            RedisProxyMembershipStore store,
            Duration membershipTtl
    ) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.membershipTtl = requirePositiveTtl(membershipTtl);
    }

    @Override
    public CompletionStage<ProxyMembershipAcquireResult> acquire(
            ProxyInstanceIdentity identity
    ) {
        ProxyInstanceIdentity nonNullIdentity = Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );

        return store.acquire(nonNullIdentity, membershipTtl)
                .handle((response, failure) -> {
                    if (failure != null) {
                        return failedOrUnavailableAcquire(failure);
                    }

                    try {
                        return completed(handleAcquireResponse(
                                nonNullIdentity,
                                response
                        ));
                    } catch (RuntimeException exception) {
                        return failedStage(exception);
                    }
                })
                .thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<ProxyMembershipRenewResult> renew(
            ProxyMembershipLease expected
    ) {
        ProxyMembershipLease nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );

        return store.renew(nonNullExpected, membershipTtl)
                .handle((response, failure) -> {
                    if (failure != null) {
                        return failedOrUnavailableRenew(failure);
                    }

                    try {
                        return completed(handleRenewResponse(
                                nonNullExpected,
                                response
                        ));
                    } catch (RuntimeException exception) {
                        return failedStage(exception);
                    }
                })
                .thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<Boolean> releaseIfOwned(
            ProxyMembershipLease expected
    ) {
        return store.releaseIfOwned(
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                )
        );
    }

    private ProxyMembershipAcquireResult handleAcquireResponse(
            ProxyInstanceIdentity identity,
            RedisProxyMembershipAcquireResponse response
    ) {
        Objects.requireNonNull(response, "response cannot be null");

        return switch (response.status()) {
            case ACQUIRED -> ProxyMembershipAcquireResult.acquired(
                    requireAcquireLease(identity, response)
            );
            case ALREADY_OWNED -> ProxyMembershipAcquireResult.alreadyOwned(
                    requireAcquireLease(identity, response)
            );
            case OWNED_BY_OTHER_INCARNATION ->
                    ProxyMembershipAcquireResult.withoutLease(
                            ProxyMembershipAcquireResult.Status
                                    .OWNED_BY_OTHER_INCARNATION
                    );
            case CORRUPT -> throw new RedisProxyMembershipInvalidStateException(
                    "Redis proxy membership lease state is corrupt"
            );
        };
    }

    private ProxyMembershipRenewResult handleRenewResponse(
            ProxyMembershipLease expected,
            RedisProxyMembershipRenewResponse response
    ) {
        Objects.requireNonNull(response, "response cannot be null");

        return switch (response.status()) {
            case RENEWED -> {
                ProxyMembershipLease lease = response.lease().orElseThrow(
                        () -> new RedisProxyMembershipInvalidStateException(
                                "Redis renew response omitted lease"
                        )
                );
                if (!lease.equals(expected)) {
                    throw new RedisProxyMembershipInvalidStateException(
                            "Redis renew response returned a mismatched lease"
                    );
                }
                yield ProxyMembershipRenewResult.renewed(lease);
            }
            case NOT_FOUND -> ProxyMembershipRenewResult.withoutLease(
                    ProxyMembershipRenewResult.Status.NOT_FOUND
            );
            case NOT_OWNER -> ProxyMembershipRenewResult.withoutLease(
                    ProxyMembershipRenewResult.Status.NOT_OWNER
            );
            case CONFLICT -> ProxyMembershipRenewResult.withoutLease(
                    ProxyMembershipRenewResult.Status.CONFLICT
            );
            case CORRUPT -> throw new RedisProxyMembershipInvalidStateException(
                    "Redis proxy membership lease state is corrupt"
            );
        };
    }

    private ProxyMembershipLease requireAcquireLease(
            ProxyInstanceIdentity identity,
            RedisProxyMembershipAcquireResponse response
    ) {
        ProxyMembershipLease lease = response.lease().orElseThrow(
                () -> new RedisProxyMembershipInvalidStateException(
                        "Redis acquire response omitted lease"
                )
        );

        if (!lease.owner().equals(identity)) {
            throw new RedisProxyMembershipInvalidStateException(
                    "Redis acquire response returned a mismatched owner"
            );
        }

        return lease;
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(
                ttl,
                "membershipTtl cannot be null"
        );
        if (nonNullTtl.isZero() || nonNullTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "membershipTtl must be positive"
            );
        }
        if (nonNullTtl.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "membershipTtl must be at least one millisecond"
            );
        }
        return nonNullTtl;
    }

    private CompletionStage<ProxyMembershipAcquireResult>
    failedOrUnavailableAcquire(Throwable failure) {
        if (RedisProxyMembershipFailures.isOperational(failure)) {
            return completed(
                    ProxyMembershipAcquireResult.withoutLease(
                            ProxyMembershipAcquireResult.Status
                                    .COORDINATION_UNAVAILABLE
                    )
            );
        }
        return failedStage(RedisProxyMembershipFailures.unwrap(failure));
    }

    private CompletionStage<ProxyMembershipRenewResult>
    failedOrUnavailableRenew(Throwable failure) {
        if (RedisProxyMembershipFailures.isOperational(failure)) {
            return completed(
                    ProxyMembershipRenewResult.withoutLease(
                            ProxyMembershipRenewResult.Status
                                    .COORDINATION_UNAVAILABLE
                    )
            );
        }
        return failedStage(RedisProxyMembershipFailures.unwrap(failure));
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failedStage(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }
}

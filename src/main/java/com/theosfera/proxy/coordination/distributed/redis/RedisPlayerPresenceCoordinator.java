package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.DistributedPlayerPresence;
import com.theosfera.proxy.coordination.PlayerPresenceCoordinator;
import com.theosfera.proxy.coordination.PlayerPresencePublishRequest;
import com.theosfera.proxy.coordination.PlayerPresencePublishResult;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveRequest;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class RedisPlayerPresenceCoordinator
        implements PlayerPresenceCoordinator {

    private final RedisPlayerPresenceStore store;
    private final Duration presenceTtl;

    public RedisPlayerPresenceCoordinator(
            RedisScriptingAsyncCommands<String, String> commands,
            Duration presenceTtl
    ) {
        this(
                commands,
                presenceTtl,
                RedisPlayerPresenceKeyspace.defaultKeyspace(),
                RedisPlayerSessionKeyspace.defaultKeyspace()
        );
    }

    public RedisPlayerPresenceCoordinator(
            RedisScriptingAsyncCommands<String, String> commands,
            Duration presenceTtl,
            RedisPlayerPresenceKeyspace presenceKeyspace,
            RedisPlayerSessionKeyspace sessionKeyspace
    ) {
        this(
                new LettuceRedisPlayerPresenceStore(
                        commands,
                        presenceKeyspace,
                        sessionKeyspace
                ),
                presenceTtl
        );
    }

    RedisPlayerPresenceCoordinator(
            RedisPlayerPresenceStore store,
            Duration presenceTtl
    ) {
        this.store = Objects.requireNonNull(
                store,
                "store cannot be null"
        );
        this.presenceTtl = requirePositiveTtl(presenceTtl);
    }

    @Override
    public CompletionStage<PlayerPresencePublishResult> publish(
            PlayerPresencePublishRequest request
    ) {
        PlayerPresencePublishRequest nonNullRequest =
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                );

        return store.publish(nonNullRequest, presenceTtl)
                .handle((result, failure) -> {
                    if (failure == null) {
                        return completed(result);
                    }

                    Throwable cause = unwrap(failure);
                    if (cause instanceof RedisPlayerPresenceInvalidStateException) {
                        return RedisPlayerPresenceCoordinator
                                .<PlayerPresencePublishResult>failed(cause);
                    }

                    return completed(
                            PlayerPresencePublishResult.withoutPresence(
                                    PlayerPresencePublishResult.Status
                                            .COORDINATION_UNAVAILABLE
                            )
                    );
                })
                .thenCompose(stage -> stage);
    }

    @Override
    public CompletionStage<Optional<DistributedPlayerPresence>> find(
            UUID playerId
    ) {
        return store.find(
                Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                )
        );
    }

    @Override
    public CompletionStage<PlayerPresenceRemoveResult> removeIfOwned(
            PlayerPresenceRemoveRequest request
    ) {
        PlayerPresenceRemoveRequest nonNullRequest =
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                );

        return store.removeIfOwned(nonNullRequest)
                .handle((result, failure) -> {
                    if (failure == null) {
                        return completed(result);
                    }

                    Throwable cause = unwrap(failure);
                    if (cause instanceof RedisPlayerPresenceInvalidStateException) {
                        return RedisPlayerPresenceCoordinator
                                .<PlayerPresenceRemoveResult>failed(cause);
                    }

                    return completed(
                            new PlayerPresenceRemoveResult(
                                    PlayerPresenceRemoveResult.Status
                                            .COORDINATION_UNAVAILABLE
                            )
                    );
                })
                .thenCompose(stage -> stage);
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(
                ttl,
                "presenceTtl cannot be null"
        );
        if (nonNullTtl.isZero() || nonNullTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "presenceTtl must be positive"
            );
        }
        return nonNullTtl;
    }

    private Throwable unwrap(Throwable failure) {
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

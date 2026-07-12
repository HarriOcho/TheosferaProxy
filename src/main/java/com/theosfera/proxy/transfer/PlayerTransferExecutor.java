package com.theosfera.proxy.transfer;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PlayerTransferExecutor {

    private static final Duration DEFAULT_TIMEOUT =
            Duration.ofSeconds(10);

    private final Duration timeout;

    public PlayerTransferExecutor() {
        this(DEFAULT_TIMEOUT);
    }

    PlayerTransferExecutor(Duration timeout) {
        this.timeout = Objects.requireNonNull(
                timeout,
                "timeout cannot be null"
        );

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "timeout must be greater than zero"
            );
        }
    }

    public CompletableFuture<PlayerTransferCompletion> execute(
            Player player,
            RegisteredServer target
    ) {
        Objects.requireNonNull(
                player,
                "player cannot be null"
        );

        Objects.requireNonNull(
                target,
                "target cannot be null"
        );

        try {
            return player
                    .createConnectionRequest(target)
                    .connect()
                    .orTimeout(
                            timeout.toMillis(),
                            TimeUnit.MILLISECONDS
                    )
                    .handle(this::mapCompletion);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    PlayerTransferCompletion.failed()
            );
        }
    }

    private PlayerTransferCompletion mapCompletion(
            ConnectionRequestBuilder.Result result,
            Throwable throwable
    ) {
        if (throwable != null) {
            if (isTimeout(throwable)) {
                return PlayerTransferCompletion.timedOut();
            }

            return PlayerTransferCompletion.failed();
        }

        if (result != null && result.isSuccessful()) {
            return PlayerTransferCompletion.success();
        }

        return PlayerTransferCompletion.rejected();
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;

        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }

        return current instanceof TimeoutException;
    }
}

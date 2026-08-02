package com.theosfera.proxy.coordination.distributed.redis;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class RedisPlayerSessionFailures {

    private RedisPlayerSessionFailures() {
    }

    static boolean isOperational(Throwable failure) {
        Throwable cause = unwrap(failure);

        return cause instanceof RedisConnectionException
                || cause instanceof RedisCommandTimeoutException;
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;

        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }
}

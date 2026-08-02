package com.theosfera.proxy.coordination.distributed.redis;

final class RedisPlayerSessionInvalidStateException
        extends RuntimeException {

    RedisPlayerSessionInvalidStateException(String message) {
        super(message);
    }

    RedisPlayerSessionInvalidStateException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}

package com.theosfera.proxy.coordination.distributed.redis;

public final class RedisBackendBootstrapInvalidStateException
        extends IllegalStateException {

    public RedisBackendBootstrapInvalidStateException(String message) {
        super(message);
    }

    public RedisBackendBootstrapInvalidStateException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}

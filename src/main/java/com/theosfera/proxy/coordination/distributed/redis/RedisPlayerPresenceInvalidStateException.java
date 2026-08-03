package com.theosfera.proxy.coordination.distributed.redis;

public final class RedisPlayerPresenceInvalidStateException
        extends IllegalStateException {

    public RedisPlayerPresenceInvalidStateException(String message) {
        super(message);
    }

    public RedisPlayerPresenceInvalidStateException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}

package com.theosfera.proxy.coordination.distributed.redis;

public final class RedisProxyMembershipInvalidStateException
        extends IllegalStateException {

    public RedisProxyMembershipInvalidStateException(String message) {
        super(message);
    }

    public RedisProxyMembershipInvalidStateException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}

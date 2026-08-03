package com.theosfera.proxy.coordination.distributed.redis;

final class RedisBackendCapacityInvalidStateException
        extends RuntimeException {

    RedisBackendCapacityInvalidStateException(String message) {
        super(message);
    }
}

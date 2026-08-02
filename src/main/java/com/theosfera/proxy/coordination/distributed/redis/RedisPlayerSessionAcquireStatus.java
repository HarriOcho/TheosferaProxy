package com.theosfera.proxy.coordination.distributed.redis;

enum RedisPlayerSessionAcquireStatus {
    ACQUIRED,
    ALREADY_OWNED,
    OWNED_BY_OTHER_PROXY,
    CONFLICT,
    CORRUPT
}

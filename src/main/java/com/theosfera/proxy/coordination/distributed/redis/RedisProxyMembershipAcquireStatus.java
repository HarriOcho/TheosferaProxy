package com.theosfera.proxy.coordination.distributed.redis;

enum RedisProxyMembershipAcquireStatus {
    ACQUIRED,
    ALREADY_OWNED,
    OWNED_BY_OTHER_INCARNATION,
    CORRUPT
}

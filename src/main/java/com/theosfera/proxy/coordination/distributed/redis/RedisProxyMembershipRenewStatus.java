package com.theosfera.proxy.coordination.distributed.redis;

enum RedisProxyMembershipRenewStatus {
    RENEWED,
    NOT_FOUND,
    NOT_OWNER,
    CONFLICT,
    CORRUPT
}

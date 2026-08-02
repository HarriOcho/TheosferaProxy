package com.theosfera.proxy.coordination.distributed.redis;

enum RedisPlayerSessionRenewStatus {
    RENEWED,
    NOT_FOUND,
    NOT_OWNER,
    CONFLICT,
    CORRUPT
}

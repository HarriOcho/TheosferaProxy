package com.theosfera.proxy.coordination.distributed.redis;

enum RedisBackendBootstrapReleaseStatus {
    RELEASED,
    NOT_FOUND,
    NOT_OWNER,
    CONFLICT,
    MEMBERSHIP_NOT_FOUND,
    NOT_MEMBERSHIP_OWNER,
    CORRUPT
}

package com.theosfera.proxy.coordination.distributed.redis;

enum RedisBackendBootstrapAcquireStatus {
    ACQUIRED,
    ALREADY_OWNED,
    TARGET_BUSY,
    REQUEST_ID_CONFLICT,
    MEMBERSHIP_NOT_FOUND,
    NOT_MEMBERSHIP_OWNER,
    CORRUPT
}

package com.theosfera.proxy.coordination.distributed.redis;

import java.util.Objects;

record RedisBackendBootstrapReleaseResponse(
        RedisBackendBootstrapReleaseStatus status
) {

    RedisBackendBootstrapReleaseResponse {
        Objects.requireNonNull(status, "status cannot be null");
    }
}

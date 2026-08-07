package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendBootstrapAcquireRequest;
import com.theosfera.proxy.coordination.BackendBootstrapLease;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

interface RedisBackendBootstrapStore {

    CompletionStage<RedisBackendBootstrapAcquireResponse> acquire(
            BackendBootstrapAcquireRequest request,
            Duration ttl
    );

    CompletionStage<RedisBackendBootstrapRenewResponse> renew(
            BackendBootstrapLease expected,
            Duration ttl
    );

    CompletionStage<RedisBackendBootstrapReleaseResponse> releaseIfOwned(
            BackendBootstrapLease expected
    );
}

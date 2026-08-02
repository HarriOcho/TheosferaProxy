package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

interface RedisPlayerSessionStore {

    CompletionStage<RedisPlayerSessionAcquireResponse> acquire(
            PlayerSessionLeaseRequest request,
            Duration ttl
    );

    CompletionStage<RedisPlayerSessionRenewResponse> renew(
            PlayerSessionLease expected,
            Duration ttl
    );

    CompletionStage<Boolean> releaseIfOwned(
            PlayerSessionLease expected
    );
}

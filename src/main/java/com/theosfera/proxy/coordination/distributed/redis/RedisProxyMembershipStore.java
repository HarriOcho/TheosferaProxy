package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

interface RedisProxyMembershipStore {

    CompletionStage<RedisProxyMembershipAcquireResponse> acquire(
            ProxyInstanceIdentity identity,
            Duration ttl
    );

    CompletionStage<RedisProxyMembershipRenewResponse> renew(
            ProxyMembershipLease expected,
            Duration ttl
    );

    CompletionStage<Boolean> releaseIfOwned(
            ProxyMembershipLease expected
    );
}

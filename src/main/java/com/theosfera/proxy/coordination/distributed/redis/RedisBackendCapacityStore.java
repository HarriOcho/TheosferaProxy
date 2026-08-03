package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

interface RedisBackendCapacityStore {

    CompletionStage<BackendCapacityReserveResult> reserve(
            BackendCapacityReserveRequest request,
            int capacity,
            Duration ttl
    );

    CompletionStage<Boolean> releaseIfOwned(
            BackendCapacityReserveRequest expected
    );

    CompletionStage<Integer> reservedCount(String backendName);
}

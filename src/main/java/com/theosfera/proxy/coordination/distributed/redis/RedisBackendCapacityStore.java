package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.transfer.BackendCapacityReservation;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

interface RedisBackendCapacityStore {

    CompletionStage<BackendCapacityReserveResult> reserve(
            BackendCapacityReservation reservation,
            int capacity,
            Duration ttl
    );

    CompletionStage<Boolean> releaseIfOwned(
            BackendCapacityReservation expected
    );

    CompletionStage<Integer> reservedCount(String backendName);
}

package com.theosfera.proxy.coordination.distributed.redis;

import java.util.concurrent.CompletionStage;

interface RedisBackendOccupancyStore {

    CompletionStage<Integer> countPresentPlayers(String backendName);
}

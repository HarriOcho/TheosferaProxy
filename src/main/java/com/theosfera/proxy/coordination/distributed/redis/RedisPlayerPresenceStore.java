package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.DistributedPlayerPresence;
import com.theosfera.proxy.coordination.PlayerPresencePublishRequest;
import com.theosfera.proxy.coordination.PlayerPresencePublishResult;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveRequest;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

interface RedisPlayerPresenceStore {

    CompletionStage<PlayerPresencePublishResult> publish(
            PlayerPresencePublishRequest request,
            Duration ttl
    );

    CompletionStage<Optional<DistributedPlayerPresence>> find(
            UUID playerId
    );

    CompletionStage<PlayerPresenceRemoveResult> removeIfOwned(
            PlayerPresenceRemoveRequest request
    );
}

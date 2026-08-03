package com.theosfera.proxy.coordination;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PlayerPresenceCoordinator {

    CompletionStage<PlayerPresencePublishResult> publish(
            PlayerPresencePublishRequest request
    );

    CompletionStage<Optional<DistributedPlayerPresence>> find(
            UUID playerId
    );

    CompletionStage<PlayerPresenceRemoveResult> removeIfOwned(
            PlayerPresenceRemoveRequest request
    );
}

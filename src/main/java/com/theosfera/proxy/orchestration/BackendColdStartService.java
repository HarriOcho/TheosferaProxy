package com.theosfera.proxy.orchestration;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface BackendColdStartService {

    CompletionStage<BackendColdStartResult> start(
            String targetBackendName,
            UUID requestId,
            UUID playerId
    );
}

package com.theosfera.proxy.orchestration;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface BackendOrchestrationProvider {

    CompletionStage<BackendStartResult> requestStart(
            BackendStartRequest request
    );
}

package com.theosfera.proxy.coordination;

import java.util.concurrent.CompletionStage;

public interface BackendOccupancyCoordinator {

    CompletionStage<BackendOccupancyReadResult> read(
            String backendName
    );
}

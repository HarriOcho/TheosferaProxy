package com.theosfera.proxy.orchestration;

import java.time.Duration;

@FunctionalInterface
public interface BackendReadinessScheduler {

    Handle schedule(Runnable task, Duration delay);

    @FunctionalInterface
    interface Handle {
        void cancel();
    }
}

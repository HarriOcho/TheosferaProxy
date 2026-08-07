package com.theosfera.proxy.orchestration;

import java.time.Duration;

/**
 * One-shot scheduler used by a backend startup operation for retries and its
 * independent total timeout.
 */
@FunctionalInterface
public interface BackendStartupScheduler {

    Handle schedule(Runnable task, Duration delay);

    @FunctionalInterface
    interface Handle {
        void cancel();
    }
}

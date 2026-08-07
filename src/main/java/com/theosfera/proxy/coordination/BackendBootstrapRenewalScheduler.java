package com.theosfera.proxy.coordination;

import java.time.Duration;

/**
 * Scheduling boundary used by one backend bootstrap ownership lifecycle.
 */
public interface BackendBootstrapRenewalScheduler {

    Handle schedule(
            Runnable task,
            Duration interval
    );

    interface Handle {
        void cancel();
    }
}

package com.theosfera.proxy.session;

import java.time.Duration;

public interface PlayerPresenceRenewalScheduler {

    Handle schedule(Runnable task, Duration interval);

    @FunctionalInterface
    interface Handle {
        void cancel();
    }
}

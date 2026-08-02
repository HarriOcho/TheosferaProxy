package com.theosfera.proxy.session;

import java.time.Duration;

public interface PlayerSessionRenewalScheduler {

    Handle schedule(Runnable task, Duration interval);

    @FunctionalInterface
    interface Handle {
        void cancel();
    }
}

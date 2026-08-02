package com.theosfera.proxy.coordination;

import java.time.Duration;

public interface ProxyMembershipRenewalScheduler {

    Handle schedule(
            Runnable task,
            Duration interval
    );

    interface Handle {
        void cancel();
    }
}

package com.theosfera.proxy.coordination;

import java.util.concurrent.CompletionStage;

public interface PlayerSessionCoordinator {

    CompletionStage<PlayerSessionAcquireResult> acquire(
            PlayerSessionLeaseRequest request
    );

    CompletionStage<PlayerSessionRenewResult> renew(
            PlayerSessionLease expected
    );

    CompletionStage<Boolean> releaseIfOwned(
            PlayerSessionLease expected
    );
}

package com.theosfera.proxy.coordination;

import java.util.concurrent.CompletionStage;

public interface ProxyMembershipCoordinator {

    CompletionStage<ProxyMembershipAcquireResult> acquire(
            ProxyInstanceIdentity identity
    );

    CompletionStage<ProxyMembershipRenewResult> renew(
            ProxyMembershipLease expected
    );

    CompletionStage<Boolean> releaseIfOwned(
            ProxyMembershipLease expected
    );
}

package com.theosfera.proxy.coordination;

import java.util.concurrent.CompletionStage;

/**
 * Distributed coordination boundary for exclusive backend bootstrap ownership.
 *
 * <p>A successful lease grants one Proxy incarnation the fenced right to
 * coordinate bootstrap of one backend. It does not prove that the backend
 * process is running, authenticated or healthy.</p>
 */
public interface BackendBootstrapCoordinator {

    CompletionStage<BackendBootstrapAcquireResult> acquire(
            BackendBootstrapAcquireRequest request
    );

    CompletionStage<BackendBootstrapRenewResult> renew(
            BackendBootstrapLease expected
    );

    CompletionStage<BackendBootstrapReleaseResult> releaseIfOwned(
            BackendBootstrapLease expected
    );
}

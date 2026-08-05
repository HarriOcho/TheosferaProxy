package com.theosfera.proxy.coordination;

import java.util.concurrent.CompletionStage;

/**
 * Lifecycle boundary between confirmed distributed player presence and an
 * outstanding backend-capacity reservation handoff.
 *
 * <p>The boundary carries only the exact session lease and backend identity;
 * session/presence code does not need to know transfer or Redis details.</p>
 */
public interface BackendCapacityHandoffLifecycle {

    void onPresenceConfirmed(
            PlayerSessionLease sessionLease,
            String backendName
    );

    CompletionStage<Boolean> releaseForDisconnect(
            PlayerSessionLease sessionLease
    );
}

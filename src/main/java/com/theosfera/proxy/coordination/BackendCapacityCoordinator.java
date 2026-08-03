package com.theosfera.proxy.coordination;

import java.util.concurrent.CompletionStage;

/**
 * Coordination boundary for backend capacity reservations.
 *
 * <p>The implementation is responsible for deciding capacity against its
 * authoritative occupancy source. Callers provide the backend capacity and
 * the exact player-session lease that authorizes the reservation, but must
 * not pre-compute a local-only occupied count for a distributed
 * implementation.</p>
 */
public interface BackendCapacityCoordinator {

    CompletionStage<BackendCapacityReserveResult> reserve(
            BackendCapacityReserveRequest request,
            int capacity
    );

    CompletionStage<Boolean> releaseIfOwned(
            BackendCapacityReserveRequest expected
    );

    CompletionStage<Integer> reservedCount(
            String backendName
    );
}

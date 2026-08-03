package com.theosfera.proxy.coordination;

import com.theosfera.proxy.transfer.BackendCapacityReservation;

import java.util.concurrent.CompletionStage;

/**
 * Coordination boundary for backend capacity reservations.
 *
 * <p>The implementation is responsible for deciding capacity against its
 * authoritative occupancy source. Callers provide the backend capacity but
 * must not pre-compute a local-only occupied count for a distributed
 * implementation.</p>
 */
public interface BackendCapacityCoordinator {

    CompletionStage<BackendCapacityReserveResult> reserve(
            BackendCapacityReservation reservation,
            int capacity
    );

    CompletionStage<Boolean> releaseIfOwned(
            BackendCapacityReservation expected
    );

    CompletionStage<Integer> reservedCount(
            String backendName
    );
}

package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;

import java.util.Objects;

/**
 * Runtime bundle for distributed backend-capacity coordination.
 *
 * <p>The bundle deliberately owns no Redis-specific implementation details.
 * The composition root supplies authoritative coordinator implementations and
 * consumers receive one shared allocation service.</p>
 */
public record DistributedBackendCapacityRuntime(
        BackendOccupancyCoordinator occupancyCoordinator,
        BackendCapacityCoordinator capacityCoordinator,
        DistributedPlayerTransferTargetAllocationService allocationService
) {

    public DistributedBackendCapacityRuntime {
        Objects.requireNonNull(
                occupancyCoordinator,
                "occupancyCoordinator cannot be null"
        );
        Objects.requireNonNull(
                capacityCoordinator,
                "capacityCoordinator cannot be null"
        );
        Objects.requireNonNull(
                allocationService,
                "allocationService cannot be null"
        );
    }

    public static DistributedBackendCapacityRuntime create(
            BackendOccupancyCoordinator occupancyCoordinator,
            BackendCapacityCoordinator capacityCoordinator,
            TransferTargetResolver targetResolver,
            PendingPlayerTransferRegistry transferRegistry,
            PlayerSessionLeaseBindingRegistry sessionLeaseBindings
    ) {
        BackendOccupancyCoordinator nonNullOccupancy =
                Objects.requireNonNull(
                        occupancyCoordinator,
                        "occupancyCoordinator cannot be null"
                );
        BackendCapacityCoordinator nonNullCapacity =
                Objects.requireNonNull(
                        capacityCoordinator,
                        "capacityCoordinator cannot be null"
                );

        return new DistributedBackendCapacityRuntime(
                nonNullOccupancy,
                nonNullCapacity,
                new DistributedPlayerTransferTargetAllocationService(
                        Objects.requireNonNull(
                                targetResolver,
                                "targetResolver cannot be null"
                        ),
                        Objects.requireNonNull(
                                transferRegistry,
                                "transferRegistry cannot be null"
                        ),
                        Objects.requireNonNull(
                                sessionLeaseBindings,
                                "sessionLeaseBindings cannot be null"
                        ),
                        nonNullOccupancy,
                        nonNullCapacity
                )
        );
    }
}

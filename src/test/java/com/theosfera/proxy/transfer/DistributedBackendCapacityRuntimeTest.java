package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class DistributedBackendCapacityRuntimeTest {

    @Test
    void createsSharedCapacityServicesFromAuthoritativeCoordinators() {
        BackendOccupancyCoordinator occupancyCoordinator =
                mock(BackendOccupancyCoordinator.class);
        BackendCapacityCoordinator capacityCoordinator =
                mock(BackendCapacityCoordinator.class);

        DistributedBackendCapacityRuntime runtime =
                DistributedBackendCapacityRuntime.create(
                        occupancyCoordinator,
                        capacityCoordinator,
                        mock(TransferTargetResolver.class),
                        new PendingPlayerTransferRegistry(),
                        new PlayerSessionLeaseBindingRegistry(),
                        mock(Logger.class)
                );

        assertSame(
                occupancyCoordinator,
                runtime.occupancyCoordinator()
        );
        assertSame(
                capacityCoordinator,
                runtime.capacityCoordinator()
        );
        assertNotNull(runtime.allocationService());
        assertNotNull(runtime.releaseService());
        assertNotNull(runtime.handoffService());
    }
}

package com.theosfera.proxy.transfer;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DistributedPlayerTransferTargetAllocationColdStartContractTest {

    @Test
    void bootstrapRequiredCanExistWithoutTransferOrCapacityArtifacts() {
        RegisteredServer target = mock(RegisteredServer.class);
        TransferTargetResolution resolution =
                TransferTargetResolution.bootstrapRequired(target);

        DistributedPlayerTransferTargetAllocation allocation =
                DistributedPlayerTransferTargetAllocation.bootstrapRequired(
                        resolution
                );

        assertTrue(allocation.isBootstrapRequired());
        assertFalse(allocation.isAllocated());
        assertTrue(allocation.targetResolution().requiresBootstrap());
        assertThrows(
                NullPointerException.class,
                allocation::requireTransfer
        );
        assertThrows(
                NullPointerException.class,
                allocation::requireCapacityRequest
        );
    }

    @Test
    void ordinaryResolvedTargetStillRequiresAllocationArtifacts() {
        RegisteredServer target = mock(RegisteredServer.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> DistributedPlayerTransferTargetAllocation.unavailable(
                        TransferTargetResolution.resolved(target)
                )
        );
    }

    @Test
    void bootstrapFactoryRejectsNonBootstrapResolution() {
        RegisteredServer target = mock(RegisteredServer.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> DistributedPlayerTransferTargetAllocation
                        .bootstrapRequired(
                                TransferTargetResolution.resolved(target)
                        )
        );
    }
}

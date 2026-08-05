package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class DistributedTransferTestRuntime {

    private DistributedTransferTestRuntime() {
    }

    public static DistributedPlayerTransferRetryCoordinator retryCoordinator(
            BackendBootstrapRegistry bootstrapRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            PlayerTransferExecutor transferExecutor,
            Logger logger,
            Player player,
            java.util.UUID requestId,
            String sourceBackendName,
            BackendType targetBackendType,
            TransferTargetResolution resolution,
            Supplier<PlayerSessionLease> leaseSupplier
    ) {
        Objects.requireNonNull(
                resolution,
                "resolution cannot be null"
        );

        String targetBackendName = resolution
                .resolvedTarget()
                .orElseThrow()
                .getServerInfo()
                .getName();

        DistributedPlayerTransferTargetAllocationService allocationService =
                mock(DistributedPlayerTransferTargetAllocationService.class);

        when(allocationService.allocate(
                same(player),
                eq(requestId),
                eq(sourceBackendName),
                eq(targetBackendType),
                anyLong(),
                anySet()
        )).thenAnswer(invocation -> {
            long requestedAt = invocation.getArgument(4);
            PlayerSessionLease lease = Objects.requireNonNull(
                    leaseSupplier.get(),
                    "leaseSupplier returned null"
            );

            PendingPlayerTransfer transfer = new PendingPlayerTransfer(
                    requestId,
                    player.getUniqueId(),
                    sourceBackendName,
                    targetBackendName,
                    requestedAt
            );
            BackendCapacityReserveRequest capacityRequest =
                    new BackendCapacityReserveRequest(
                            new BackendCapacityReservation(
                                    requestId,
                                    player.getUniqueId(),
                                    targetBackendName
                            ),
                            lease
                    );

            return CompletableFuture.completedFuture(
                    DistributedPlayerTransferTargetAllocation.allocated(
                            resolution,
                            transfer,
                            capacityRequest,
                            BackendCapacityReserveResult.Status.RESERVED
                    )
            );
        });

        BackendCapacityCoordinator capacityCoordinator =
                mock(BackendCapacityCoordinator.class);
        DistributedBackendCapacityReleaseService releaseService =
                new DistributedBackendCapacityReleaseService(
                        capacityCoordinator,
                        logger
                );
        BackendCapacityHandoffService handoffService =
                new BackendCapacityHandoffService(
                        capacityCoordinator,
                        new BackendCapacityHandoffRegistry(),
                        logger
                );
        DistributedPlayerTransferAttemptLifecycle attemptLifecycle =
                new DistributedPlayerTransferAttemptLifecycle(
                        transferRegistry,
                        bootstrapRegistry,
                        releaseService,
                        handoffService,
                        logger
                );

        return new DistributedPlayerTransferRetryCoordinator(
                bootstrapRegistry,
                allocationService,
                transferExecutor,
                attemptLifecycle,
                logger
        );
    }
}

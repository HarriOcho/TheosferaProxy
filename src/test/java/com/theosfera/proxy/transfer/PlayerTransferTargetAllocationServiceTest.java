package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerTransferTargetAllocationServiceTest {

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final long REQUESTED_AT = 1_750_000_000_000L;

    private TransferTargetResolver resolver;
    private PendingPlayerTransferRegistry transferRegistry;
    private PlayerTransferTargetAllocationService service;

    @BeforeEach
    void setUp() {
        resolver = mock(TransferTargetResolver.class);
        transferRegistry = new PendingPlayerTransferRegistry();
        service = new PlayerTransferTargetAllocationService(
                resolver,
                transferRegistry
        );
    }

    @Test
    void retriesAnotherTargetWhenLastSlotWasReservedConcurrently() {
        RegisteredServer first = server("lobby-1");
        RegisteredServer second = server("lobby-2");

        when(resolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution.resolved(first)
                );

        when(resolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(second));

        when(resolver.reserveCapacity(any(), any()))
                .thenReturn(
                        BackendCapacityReservationResult.NO_CAPACITY,
                        BackendCapacityReservationResult.RESERVED
                );

        PlayerTransferTargetAllocation allocation =
                service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        BackendType.LOBBY,
                        REQUESTED_AT
                );

        assertTrue(allocation.isAllocated());
        assertSame(
                second,
                allocation.targetResolution()
                        .resolvedTarget()
                        .orElseThrow()
        );
        assertEquals(
                "lobby-2",
                allocation.requireTransfer().targetBackendName()
        );
        assertEquals(1, transferRegistry.snapshotByPlayer().size());
    }

    @Test
    void reportsNoCapacityAfterAllCandidatesRejectReservation() {
        RegisteredServer target = server("lobby-1");

        when(resolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        when(resolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.notConfigured());

        when(resolver.reserveCapacity(any(), any()))
                .thenReturn(
                        BackendCapacityReservationResult.NO_CAPACITY
                );

        PlayerTransferTargetAllocation allocation =
                service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        BackendType.LOBBY,
                        REQUESTED_AT
                );

        assertEquals(
                TransferTargetResolutionStatus.NO_CAPACITY,
                allocation.targetResolution().status()
        );
        assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
    }

    @Test
    void preservesPlayerBusyRegistrationFailure() {
        RegisteredServer target = server("lobby-1");

        transferRegistry.register(
                new PendingPlayerTransfer(
                        UUID.randomUUID(),
                        PLAYER_ID,
                        "skyblock-1",
                        "lobby-9",
                        REQUESTED_AT
                )
        );

        when(resolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        PlayerTransferTargetAllocation allocation =
                service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        BackendType.LOBBY,
                        REQUESTED_AT
                );

        assertEquals(
                PlayerTransferRegistrationResult.PLAYER_BUSY,
                allocation.registrationResult()
        );
        verify(resolver, org.mockito.Mockito.never())
                .reserveCapacity(any(), any());
    }

    @Test
    void detectsCurrentTargetBeforeRegisteringTransfer() {
        RegisteredServer target = server("lobby-1");

        when(resolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        PlayerTransferTargetAllocation allocation =
                service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1",
                        BackendType.LOBBY,
                        REQUESTED_AT
                );

        assertTrue(allocation.isSameTarget());
        assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
    }

    @Test
    void rollsBackPendingTransferWhenReservationThrows() {
        RegisteredServer target = server("lobby-1");
        RuntimeException exception = new RuntimeException("boom");

        when(resolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        when(resolver.reserveCapacity(any(), any()))
                .thenThrow(exception);

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        BackendType.LOBBY,
                        REQUESTED_AT
                )
        );

        assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
    }

    @Test
    void startsWithCallerProvidedExclusions() {
        RegisteredServer target = server("lobby-2");

        when(resolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(target));

        when(resolver.reserveCapacity(any(), any()))
                .thenReturn(BackendCapacityReservationResult.RESERVED);

        PlayerTransferTargetAllocation allocation =
                service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        BackendType.LOBBY,
                        REQUESTED_AT,
                        Set.of("lobby-1")
                );

        assertTrue(allocation.isAllocated());
        assertEquals(
                "lobby-2",
                allocation.requireTransfer().targetBackendName()
        );
        verify(resolver, never()).resolve(BackendType.LOBBY);
    }

    @Test
    void combinesInitialExclusionsWithCapacityRaceExclusions() {
        RegisteredServer first = server("lobby-2");
        RegisteredServer second = server("lobby-3");

        when(resolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetResolution.resolved(first));

        when(resolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1", "lobby-2")
        )).thenReturn(TransferTargetResolution.resolved(second));

        when(resolver.reserveCapacity(any(), any()))
                .thenReturn(
                        BackendCapacityReservationResult.NO_CAPACITY,
                        BackendCapacityReservationResult.RESERVED
                );

        PlayerTransferTargetAllocation allocation =
                service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        BackendType.LOBBY,
                        REQUESTED_AT,
                        Set.of("lobby-1")
                );

        assertTrue(allocation.isAllocated());
        assertEquals(
                "lobby-3",
                allocation.requireTransfer().targetBackendName()
        );
    }

    @Test
    void existingAllocateOverloadStillUsesNoInitialExclusions() {
        RegisteredServer target = server("lobby-1");

        when(resolver.resolve(BackendType.LOBBY))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(resolver.reserveCapacity(any(), any()))
                .thenReturn(BackendCapacityReservationResult.RESERVED);

        PlayerTransferTargetAllocation allocation =
                service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        BackendType.LOBBY,
                        REQUESTED_AT
                );

        assertTrue(allocation.isAllocated());
        verify(resolver).resolve(BackendType.LOBBY);
    }

    @Test
    void defensiveCopiesInitialExclusions() {
        RegisteredServer first = server("lobby-2");
        RegisteredServer second = server("lobby-3");
        Set<String> initialExclusions =
                new java.util.HashSet<>();

        initialExclusions.add("lobby-1");

        when(resolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenAnswer(invocation -> {
            initialExclusions.add("lobby-3");
            return TransferTargetResolution.resolved(first);
        });

        when(resolver.resolve(
                BackendType.LOBBY,
                Set.of("lobby-1", "lobby-2")
        )).thenReturn(TransferTargetResolution.resolved(second));

        when(resolver.reserveCapacity(any(), any()))
                .thenReturn(
                        BackendCapacityReservationResult.NO_CAPACITY,
                        BackendCapacityReservationResult.RESERVED
                );

        PlayerTransferTargetAllocation allocation =
                service.allocate(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        BackendType.LOBBY,
                        REQUESTED_AT,
                        initialExclusions
                );

        assertTrue(allocation.isAllocated());
        assertEquals(
                "lobby-3",
                allocation.requireTransfer().targetBackendName()
        );
        verify(resolver, org.mockito.Mockito.atLeastOnce()).resolve(
                BackendType.LOBBY,
                Set.of("lobby-1", "lobby-2")
        );
    }

    private RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);

        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);

        return server;
    }
}

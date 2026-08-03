package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.BackendOccupancyReadResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedPlayerTransferTargetAllocationServiceTest {

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final long REQUESTED_AT = 1_750_000_000_000L;

    private TransferTargetResolver resolver;
    private PendingPlayerTransferRegistry transferRegistry;
    private PlayerSessionLeaseBindingRegistry sessionLeaseBindings;
    private BackendOccupancyCoordinator occupancyCoordinator;
    private BackendCapacityCoordinator capacityCoordinator;
    private Player player;
    private PlayerSessionLease lease;
    private DistributedPlayerTransferTargetAllocationService service;

    @BeforeEach
    void setUp() {
        resolver = mock(TransferTargetResolver.class);
        transferRegistry = new PendingPlayerTransferRegistry();
        sessionLeaseBindings = mock(PlayerSessionLeaseBindingRegistry.class);
        occupancyCoordinator = mock(BackendOccupancyCoordinator.class);
        capacityCoordinator = mock(BackendCapacityCoordinator.class);
        player = mock(Player.class);
        lease = lease();

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(sessionLeaseBindings.find(player)).thenReturn(Optional.of(lease));

        service = new DistributedPlayerTransferTargetAllocationService(
                resolver,
                transferRegistry,
                sessionLeaseBindings,
                occupancyCoordinator,
                capacityCoordinator
        );
    }

    @Test
    void selectsLeastLoadedCandidateUsingGlobalRedisCounts() {
        BackendTargetCandidate first = candidate("lobby-1", 100, 100);
        BackendTargetCandidate second = candidate("lobby-2", 100, 90);
        configured(first, second);
        globalLoad("lobby-1", 70, 5);
        globalLoad("lobby-2", 20, 3);

        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenAnswer(invocation -> {
                    BackendCapacityReserveRequest request =
                            invocation.getArgument(0);
                    return CompletableFuture.completedFuture(
                            BackendCapacityReserveResult.withReservation(
                                    BackendCapacityReserveResult.Status.RESERVED,
                                    request.reservation()
                            )
                    );
                });

        DistributedPlayerTransferTargetAllocation allocation = allocate();

        assertTrue(allocation.isAllocated());
        assertEquals(
                "lobby-2",
                allocation.requireTransfer().targetBackendName()
        );
        assertEquals(
                BackendCapacityReserveResult.Status.RESERVED,
                allocation.capacityStatus()
        );

        ArgumentCaptor<BackendCapacityReserveRequest> requestCaptor =
                ArgumentCaptor.forClass(BackendCapacityReserveRequest.class);
        verify(capacityCoordinator).reserve(requestCaptor.capture(), anyInt());
        assertSame(lease, requestCaptor.getValue().sessionLease());
        assertEquals(
                "lobby-2",
                requestCaptor.getValue().reservation().backendName()
        );
    }

    @Test
    void treatsExactAlreadyReservedResultAsAllocated() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configured(target);
        globalLoad("lobby-1", 10, 1);

        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenAnswer(invocation -> {
                    BackendCapacityReserveRequest request =
                            invocation.getArgument(0);
                    return CompletableFuture.completedFuture(
                            BackendCapacityReserveResult.withReservation(
                                    BackendCapacityReserveResult.Status
                                            .ALREADY_RESERVED,
                                    request.reservation()
                            )
                    );
                });

        DistributedPlayerTransferTargetAllocation allocation = allocate();

        assertTrue(allocation.isAllocated());
        assertEquals(
                BackendCapacityReserveResult.Status.ALREADY_RESERVED,
                allocation.capacityStatus()
        );
        assertEquals(1, transferRegistry.snapshotByPlayer().size());
    }

    @Test
    void retriesAnotherCandidateAfterAtomicNoCapacityRace() {
        BackendTargetCandidate first = candidate("lobby-1", 100, 100);
        BackendTargetCandidate second = candidate("lobby-2", 100, 90);

        when(resolver.candidates(BackendType.LOBBY, Set.of()))
                .thenReturn(TransferTargetCandidates.configured(
                        List.of(first, second),
                        List.of()
                ));
        when(resolver.candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetCandidates.configured(
                List.of(second),
                List.of()
        ));

        globalLoad("lobby-1", 10, 0);
        globalLoad("lobby-2", 20, 0);

        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenAnswer(invocation -> {
                    BackendCapacityReserveRequest request =
                            invocation.getArgument(0);
                    if (request.reservation().backendName().equals("lobby-1")) {
                        return CompletableFuture.completedFuture(
                                BackendCapacityReserveResult.withoutReservation(
                                        BackendCapacityReserveResult.Status
                                                .NO_CAPACITY
                                )
                        );
                    }
                    return CompletableFuture.completedFuture(
                            BackendCapacityReserveResult.withReservation(
                                    BackendCapacityReserveResult.Status.RESERVED,
                                    request.reservation()
                            )
                    );
                });

        DistributedPlayerTransferTargetAllocation allocation = allocate();

        assertTrue(allocation.isAllocated());
        assertEquals(
                "lobby-2",
                allocation.requireTransfer().targetBackendName()
        );
        assertEquals(1, transferRegistry.snapshotByPlayer().size());
        verify(resolver).candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
    }

    @Test
    void mapsMissingBoundSessionLeaseWithoutCreatingPendingTransfer() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configured(target);
        globalLoad("lobby-1", 10, 0);
        when(sessionLeaseBindings.find(player)).thenReturn(Optional.empty());

        DistributedPlayerTransferTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.SESSION_NOT_FOUND,
                allocation.capacityStatus()
        );
        assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    @Test
    void preservesExplicitAuthorityAndCoordinationReservationFailures() {
        for (BackendCapacityReserveResult.Status status : List.of(
                BackendCapacityReserveResult.Status.REQUEST_ID_CONFLICT,
                BackendCapacityReserveResult.Status.SESSION_NOT_FOUND,
                BackendCapacityReserveResult.Status.NOT_SESSION_OWNER,
                BackendCapacityReserveResult.Status.OCCUPANCY_UNAVAILABLE,
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE
        )) {
            transferRegistry.clear();
            BackendTargetCandidate target = candidate(
                    "lobby-1",
                    100,
                    100
            );
            configured(target);
            globalLoad("lobby-1", 10, 0);
            when(capacityCoordinator.reserve(any(), anyInt()))
                    .thenReturn(CompletableFuture.completedFuture(
                            BackendCapacityReserveResult.withoutReservation(
                                    status
                            )
                    ));

            DistributedPlayerTransferTargetAllocation allocation = allocate();

            assertTrue(allocation.isCapacityRejected());
            assertEquals(status, allocation.capacityStatus());
            assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
        }
    }

    @Test
    void failsClosedWhenGlobalOccupancyIsUnavailable() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configured(target);

        when(occupancyCoordinator.read("lobby-1"))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendOccupancyReadResult.unavailable(
                                BackendOccupancyReadResult.Status
                                        .COORDINATION_UNAVAILABLE
                        )
                ));
        when(capacityCoordinator.reservedCount("lobby-1"))
                .thenReturn(CompletableFuture.completedFuture(0));

        DistributedPlayerTransferTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE,
                allocation.capacityStatus()
        );
        assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    @Test
    void mapsUnknownOccupancyBackendToOccupancyUnavailable() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configured(target);

        when(occupancyCoordinator.read("lobby-1"))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendOccupancyReadResult.unavailable(
                                BackendOccupancyReadResult.Status
                                        .BACKEND_NOT_FOUND
                        )
                ));
        when(capacityCoordinator.reservedCount("lobby-1"))
                .thenReturn(CompletableFuture.completedFuture(0));

        DistributedPlayerTransferTargetAllocation allocation = allocate();

        assertEquals(
                BackendCapacityReserveResult.Status.OCCUPANCY_UNAVAILABLE,
                allocation.capacityStatus()
        );
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    @Test
    void neverInventsZeroWhenDistributedReservationCountFails() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configured(target);

        when(occupancyCoordinator.read("lobby-1"))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendOccupancyReadResult.available(10)
                ));
        when(capacityCoordinator.reservedCount("lobby-1"))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("redis unavailable")
                ));

        DistributedPlayerTransferTargetAllocation allocation = allocate();

        assertEquals(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE,
                allocation.capacityStatus()
        );
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    @Test
    void reservesColdBootstrapTargetAgainstDistributedCapacity() {
        BackendTargetCandidate cold = candidate("lobby-1", 100, 100);
        when(resolver.candidates(BackendType.LOBBY, Set.of()))
                .thenReturn(TransferTargetCandidates.configured(
                        List.of(),
                        List.of(cold)
                ));
        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenAnswer(invocation -> {
                    BackendCapacityReserveRequest request =
                            invocation.getArgument(0);
                    return CompletableFuture.completedFuture(
                            BackendCapacityReserveResult.withReservation(
                                    BackendCapacityReserveResult.Status.RESERVED,
                                    request.reservation()
                            )
                    );
                });

        DistributedPlayerTransferTargetAllocation allocation = allocate();

        assertTrue(allocation.isAllocated());
        assertTrue(allocation.targetResolution().requiresBootstrap());
        verify(occupancyCoordinator, never()).read(any());
        verify(capacityCoordinator).reserve(any(), anyInt());
    }

    @Test
    void detectsSameTargetBeforeSessionOrCapacityReservation() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configured(target);
        globalLoad("lobby-1", 10, 0);

        DistributedPlayerTransferTargetAllocation allocation = service.allocate(
                player,
                REQUEST_ID,
                "lobby-1",
                BackendType.LOBBY,
                REQUESTED_AT
        ).toCompletableFuture().join();

        assertTrue(allocation.isSameTarget());
        verify(sessionLeaseBindings, never()).find(player);
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    private DistributedPlayerTransferTargetAllocation allocate() {
        return service.allocate(
                player,
                REQUEST_ID,
                "skyblock-1",
                BackendType.LOBBY,
                REQUESTED_AT
        ).toCompletableFuture().join();
    }

    private void configured(BackendTargetCandidate... candidates) {
        when(resolver.candidates(BackendType.LOBBY, Set.of()))
                .thenReturn(TransferTargetCandidates.configured(
                        List.of(candidates),
                        List.of()
                ));
    }

    private void globalLoad(
            String backendName,
            int occupancy,
            int reservations
    ) {
        when(occupancyCoordinator.read(backendName))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendOccupancyReadResult.available(occupancy)
                ));
        when(capacityCoordinator.reservedCount(backendName))
                .thenReturn(CompletableFuture.completedFuture(reservations));
    }

    private BackendTargetCandidate candidate(
            String name,
            int capacity,
            int preference
    ) {
        RegisteredServer server = server(name);
        return new BackendTargetCandidate(
                name,
                server,
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        capacity,
                        preference
                )
        );
    }

    private RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);
        return server;
    }

    private PlayerSessionLease lease() {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_000L
                ),
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.randomUUID()
                ),
                7L
        );
    }
}

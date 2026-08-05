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
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DistributedResolvedTargetAllocationServiceTest {

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();

    private TransferTargetResolver resolver;
    private PlayerSessionLeaseBindingRegistry sessionLeaseBindings;
    private BackendOccupancyCoordinator occupancyCoordinator;
    private BackendCapacityCoordinator capacityCoordinator;
    private Player player;
    private PlayerSessionLease lease;
    private DistributedResolvedTargetAllocationService service;

    @BeforeEach
    void setUp() {
        resolver = mock(TransferTargetResolver.class);
        sessionLeaseBindings = mock(PlayerSessionLeaseBindingRegistry.class);
        occupancyCoordinator = mock(BackendOccupancyCoordinator.class);
        capacityCoordinator = mock(BackendCapacityCoordinator.class);
        player = mock(Player.class);
        lease = lease();

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(sessionLeaseBindings.find(player)).thenReturn(Optional.of(lease));

        service = new DistributedResolvedTargetAllocationService(
                resolver,
                sessionLeaseBindings,
                occupancyCoordinator,
                capacityCoordinator
        );
    }

    @Test
    void selectsLeastLoadedActiveCandidateUsingGlobalRedisCounts() {
        BackendTargetCandidate first = candidate("lobby-1", 100, 100);
        BackendTargetCandidate second = candidate("lobby-2", 100, 90);
        configuredActive(List.of(first, second), List.of());
        globalLoad("lobby-1", 70, 5);
        globalLoad("lobby-2", 20, 3);
        reserveExact(
                BackendCapacityReserveResult.Status.RESERVED
        );

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isAllocated());
        assertEquals(
                "lobby-2",
                allocation.requireCapacityRequest()
                        .reservation()
                        .backendName()
        );
        assertEquals(
                BackendCapacityReserveResult.Status.RESERVED,
                allocation.capacityStatus()
        );

        ArgumentCaptor<BackendCapacityReserveRequest> requestCaptor =
                ArgumentCaptor.forClass(BackendCapacityReserveRequest.class);
        verify(capacityCoordinator).reserve(requestCaptor.capture(), anyInt());
        assertSame(lease, requestCaptor.getValue().sessionLease());
        assertEquals(REQUEST_ID, requestCaptor.getValue()
                .reservation()
                .requestId());
        assertEquals(PLAYER_ID, requestCaptor.getValue()
                .reservation()
                .playerId());
    }

    @Test
    void treatsExactAlreadyReservedAsSuccess() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configuredActive(List.of(target), List.of());
        globalLoad("lobby-1", 10, 1);
        reserveExact(
                BackendCapacityReserveResult.Status.ALREADY_RESERVED
        );

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isAllocated());
        assertEquals(
                BackendCapacityReserveResult.Status.ALREADY_RESERVED,
                allocation.capacityStatus()
        );
    }

    @Test
    void rejectsAuthWithoutConsultingResolverOrRedis() {
        DistributedResolvedTargetAllocation allocation = service.allocate(
                player,
                REQUEST_ID,
                BackendType.AUTH
        ).toCompletableFuture().join();

        assertEquals(
                TransferTargetResolutionStatus.NOT_CONFIGURED,
                allocation.targetResolution().status()
        );
        verifyNoInteractions(resolver);
        verifyNoInteractions(occupancyCoordinator);
        verifyNoInteractions(capacityCoordinator);
    }

    @Test
    void retriesAnotherActiveCandidateAfterAtomicNoCapacity() {
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

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isAllocated());
        assertEquals(
                "lobby-2",
                allocation.requireCapacityRequest()
                        .reservation()
                        .backendName()
        );
        verify(resolver).candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
    }

    @Test
    void neverFallsBackToColdCandidateWhenNoActiveCandidateExists() {
        BackendTargetCandidate cold = candidate("lobby-1", 100, 100);
        configuredActive(List.of(), List.of(cold));

        DistributedResolvedTargetAllocation allocation = allocate();

        assertEquals(
                TransferTargetResolutionStatus.NOT_AUTHENTICATED,
                allocation.targetResolution().status()
        );
        verify(occupancyCoordinator, never()).read(any());
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    @Test
    void neverFallsBackToColdCandidateAfterAtomicNoCapacity() {
        BackendTargetCandidate active = candidate("lobby-1", 100, 100);
        BackendTargetCandidate cold = candidate("lobby-2", 100, 90);

        when(resolver.candidates(BackendType.LOBBY, Set.of()))
                .thenReturn(TransferTargetCandidates.configured(
                        List.of(active),
                        List.of(cold)
                ));
        when(resolver.candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetCandidates.configured(
                List.of(),
                List.of(cold)
        ));

        globalLoad("lobby-1", 10, 0);
        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendCapacityReserveResult.withoutReservation(
                                BackendCapacityReserveResult.Status
                                        .NO_CAPACITY
                        )
                ));

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.NO_CAPACITY,
                allocation.capacityStatus()
        );
        assertEquals(
                TransferTargetResolutionStatus.NO_CAPACITY,
                allocation.targetResolution().status()
        );
        verify(capacityCoordinator).reserve(any(), anyInt());
    }

    @Test
    void failsClosedWhenGlobalOccupancyIsUnavailable() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configuredActive(List.of(target), List.of());

        when(occupancyCoordinator.read("lobby-1"))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendOccupancyReadResult.unavailable(
                                BackendOccupancyReadResult.Status
                                        .COORDINATION_UNAVAILABLE
                        )
                ));
        when(capacityCoordinator.reservedCount("lobby-1"))
                .thenReturn(CompletableFuture.completedFuture(0));

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE,
                allocation.capacityStatus()
        );
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    @Test
    void failsClosedWhenDistributedReservationCountIsUnavailable() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configuredActive(List.of(target), List.of());

        when(occupancyCoordinator.read("lobby-1"))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendOccupancyReadResult.available(10)
                ));
        when(capacityCoordinator.reservedCount("lobby-1"))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("redis unavailable")
                ));

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE,
                allocation.capacityStatus()
        );
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    @Test
    void rejectsMissingBoundSessionLease() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configuredActive(List.of(target), List.of());
        globalLoad("lobby-1", 10, 0);
        when(sessionLeaseBindings.find(player)).thenReturn(Optional.empty());

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.SESSION_NOT_FOUND,
                allocation.capacityStatus()
        );
        verify(capacityCoordinator, never()).reserve(any(), anyInt());
    }

    @Test
    void failsClosedWhenCapacityCoordinatorReturnsNullStage() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configuredActive(List.of(target), List.of());
        globalLoad("lobby-1", 10, 0);
        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenReturn(null);

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE,
                allocation.capacityStatus()
        );
    }

    @Test
    void failsClosedWhenReserveStageCompletesExceptionally() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configuredActive(List.of(target), List.of());
        globalLoad("lobby-1", 10, 0);
        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("redis unavailable")
                ));

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE,
                allocation.capacityStatus()
        );
    }

    @Test
    void failsClosedWhenReserveResultIsNull() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configuredActive(List.of(target), List.of());
        globalLoad("lobby-1", 10, 0);
        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));

        DistributedResolvedTargetAllocation allocation = allocate();

        assertTrue(allocation.isCapacityRejected());
        assertEquals(
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE,
                allocation.capacityStatus()
        );
    }

    @Test
    void preservesAuthorityAndCoordinationReservationFailures() {
        for (BackendCapacityReserveResult.Status status : List.of(
                BackendCapacityReserveResult.Status.REQUEST_ID_CONFLICT,
                BackendCapacityReserveResult.Status.SESSION_NOT_FOUND,
                BackendCapacityReserveResult.Status.NOT_SESSION_OWNER,
                BackendCapacityReserveResult.Status.OCCUPANCY_UNAVAILABLE,
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE
        )) {
            BackendTargetCandidate target = candidate(
                    "lobby-1",
                    100,
                    100
            );
            configuredActive(List.of(target), List.of());
            globalLoad("lobby-1", 10, 0);
            when(capacityCoordinator.reserve(any(), anyInt()))
                    .thenReturn(CompletableFuture.completedFuture(
                            BackendCapacityReserveResult.withoutReservation(
                                    status
                            )
                    ));

            DistributedResolvedTargetAllocation allocation = allocate();

            assertTrue(allocation.isCapacityRejected());
            assertEquals(status, allocation.capacityStatus());
        }
    }

    @Test
    void rejectsBootstrapResolutionByContract() {
        RegisteredServer server = server("lobby-1");
        TransferTargetResolution bootstrap =
                TransferTargetResolution.bootstrapRequired(server);

        assertThrows(
                IllegalArgumentException.class,
                () -> DistributedResolvedTargetAllocation.capacityRejected(
                        bootstrap,
                        BackendCapacityReserveResult.Status.NO_CAPACITY
                )
        );
    }

    @Test
    void rejectsMismatchedReservationReturnedByCoordinator() {
        BackendTargetCandidate target = candidate("lobby-1", 100, 100);
        configuredActive(List.of(target), List.of());
        globalLoad("lobby-1", 10, 0);

        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendCapacityReserveResult.withReservation(
                                BackendCapacityReserveResult.Status.RESERVED,
                                new BackendCapacityReservation(
                                        REQUEST_ID,
                                        PLAYER_ID,
                                        "lobby-2"
                                )
                        )
                ));

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> allocate()
        );

        assertInstanceOf(
                TransferTargetResolutionContractViolationException.class,
                exception.getCause()
        );
    }

    private DistributedResolvedTargetAllocation allocate() {
        return service.allocate(
                player,
                REQUEST_ID,
                BackendType.LOBBY
        ).toCompletableFuture().join();
    }

    private void configuredActive(
            List<BackendTargetCandidate> active,
            List<BackendTargetCandidate> cold
    ) {
        when(resolver.candidates(BackendType.LOBBY, Set.of()))
                .thenReturn(TransferTargetCandidates.configured(
                        active,
                        cold
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

    private void reserveExact(
            BackendCapacityReserveResult.Status status
    ) {
        when(capacityCoordinator.reserve(any(), anyInt()))
                .thenAnswer(invocation -> {
                    BackendCapacityReserveRequest request =
                            invocation.getArgument(0);
                    return CompletableFuture.completedFuture(
                            BackendCapacityReserveResult.withReservation(
                                    status,
                                    request.reservation()
                            )
                    );
                });
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

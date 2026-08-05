package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.transfer.BackendCapacityHandoffRegistrationResult;
import com.theosfera.proxy.transfer.BackendCapacityHandoffService;
import com.theosfera.proxy.transfer.BackendCapacityReservation;
import com.theosfera.proxy.transfer.DistributedBackendCapacityReleaseService;
import com.theosfera.proxy.transfer.DistributedResolvedTargetAllocation;
import com.theosfera.proxy.transfer.DistributedResolvedTargetAllocationService;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedBackendKickFailoverCoordinatorTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final Component REASON = Component.text("backend unavailable");
    private static final Set<String> EXCLUSIONS = Set.of("skyblock-1");

    private DistributedResolvedTargetAllocationService allocationService;
    private PendingPlayerFailoverRegistry failoverRegistry;
    private DistributedBackendCapacityReleaseService releaseService;
    private BackendCapacityHandoffService handoffService;
    private Player player;
    private DistributedBackendKickFailoverCoordinator coordinator;

    @BeforeEach
    void setUp() {
        allocationService = mock(DistributedResolvedTargetAllocationService.class);
        failoverRegistry = new PendingPlayerFailoverRegistry();
        releaseService = mock(DistributedBackendCapacityReleaseService.class);
        handoffService = mock(BackendCapacityHandoffService.class);
        player = mock(Player.class);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(releaseService.releaseIfOwned(any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(handoffService.registerAfterConnectionSuccess(any()))
                .thenReturn(BackendCapacityHandoffRegistrationResult.REGISTERED);

        coordinator = new DistributedBackendKickFailoverCoordinator(
                allocationService,
                failoverRegistry,
                releaseService,
                handoffService,
                mock(Logger.class)
        );
    }

    @Test
    void redirectsToAllocatedSameTypeAndKeepsPendingReservation() {
        RegisteredServer target = server("skyblock-2");
        BackendCapacityReserveRequest request = request("skyblock-2");
        when(allocationService.allocate(
                eq(player),
                any(UUID.class),
                eq(BackendType.SKYBLOCK),
                eq(EXCLUSIONS)
        )).thenReturn(CompletableFuture.completedFuture(
                allocated(target, request)
        ));

        BackendKickFailoverResolution resolution = resolve(BackendType.SKYBLOCK);

        assertEquals(BackendKickFailoverResolutionStatus.REDIRECT, resolution.status());
        assertSame(target, resolution.redirectTarget().orElseThrow());
        assertTrue(failoverRegistry.isReserved(PLAYER_ID));
        verify(handoffService, never()).registerAfterConnectionSuccess(any());
        verify(releaseService, never()).releaseIfOwned(any());
    }

    @Test
    void fallsBackToLobbyOnlyAfterOrdinaryNoCapacity() {
        RegisteredServer lobby = server("lobby-1");
        BackendCapacityReserveRequest lobbyRequest = request("lobby-1");

        when(allocationService.allocate(
                eq(player),
                any(UUID.class),
                eq(BackendType.SKYBLOCK),
                eq(EXCLUSIONS)
        )).thenReturn(CompletableFuture.completedFuture(
                DistributedResolvedTargetAllocation.capacityRejected(
                        TransferTargetResolution.noCapacity(),
                        BackendCapacityReserveResult.Status.NO_CAPACITY
                )
        ));
        when(allocationService.allocate(
                eq(player),
                any(UUID.class),
                eq(BackendType.LOBBY),
                eq(EXCLUSIONS)
        )).thenReturn(CompletableFuture.completedFuture(
                allocated(lobby, lobbyRequest)
        ));

        BackendKickFailoverResolution resolution = resolve(BackendType.SKYBLOCK);

        assertEquals(BackendKickFailoverResolutionStatus.REDIRECT, resolution.status());
        assertSame(lobby, resolution.redirectTarget().orElseThrow());
        assertTrue(failoverRegistry.isReserved(PLAYER_ID));
    }

    @Test
    void coordinationFailureIsTerminalAndNeverFallsBackToLobby() {
        when(allocationService.allocate(
                eq(player),
                any(UUID.class),
                eq(BackendType.SKYBLOCK),
                eq(EXCLUSIONS)
        )).thenReturn(CompletableFuture.completedFuture(
                DistributedResolvedTargetAllocation.capacityRejected(
                        TransferTargetResolution.noCapacity(),
                        BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE
                )
        ));

        BackendKickFailoverResolution resolution = resolve(BackendType.SKYBLOCK);

        assertEquals(BackendKickFailoverResolutionStatus.DISCONNECT, resolution.status());
        assertSame(REASON, resolution.reason().orElseThrow());
        assertFalse(failoverRegistry.isReserved(PLAYER_ID));
        verify(allocationService, never()).allocate(
                eq(player),
                any(UUID.class),
                eq(BackendType.LOBBY),
                eq(EXCLUSIONS)
        );
    }

    @Test
    void lobbyNoCapacityDisconnectsWithoutAnotherFallback() {
        when(allocationService.allocate(
                eq(player),
                any(UUID.class),
                eq(BackendType.LOBBY),
                eq(EXCLUSIONS)
        )).thenReturn(CompletableFuture.completedFuture(
                DistributedResolvedTargetAllocation.capacityRejected(
                        TransferTargetResolution.noCapacity(),
                        BackendCapacityReserveResult.Status.NO_CAPACITY
                )
        ));

        BackendKickFailoverResolution resolution = resolve(BackendType.LOBBY);

        assertEquals(BackendKickFailoverResolutionStatus.DISCONNECT, resolution.status());
        assertFalse(failoverRegistry.isReserved(PLAYER_ID));
    }

    @Test
    void duplicatePendingKickIsIgnoredBeforeAllocation() {
        assertTrue(failoverRegistry.reserve(PLAYER_ID));

        BackendKickFailoverResolution resolution = resolve(BackendType.SKYBLOCK);

        assertEquals(BackendKickFailoverResolutionStatus.IGNORED, resolution.status());
        verify(allocationService, never()).allocate(
                any(),
                any(UUID.class),
                any(),
                any()
        );
    }

    @Test
    void successfulConnectionMovesExactReservationToPresenceHandoff() {
        BackendCapacityReserveRequest request = startAllocatedFailover("skyblock-2");

        coordinator.completeSuccessfulConnection(PLAYER_ID, "skyblock-2");

        assertFalse(failoverRegistry.isReserved(PLAYER_ID));
        verify(handoffService).registerAfterConnectionSuccess(request);
        verify(releaseService, never()).releaseIfOwned(request);
    }

    @Test
    void successfulConnectionWithDifferentBackendReleasesExactReservation() {
        BackendCapacityReserveRequest request = startAllocatedFailover("skyblock-2");

        coordinator.completeSuccessfulConnection(PLAYER_ID, "lobby-1");

        assertFalse(failoverRegistry.isReserved(PLAYER_ID));
        verify(releaseService).releaseIfOwned(request);
        verify(handoffService, never()).registerAfterConnectionSuccess(any());
    }

    @Test
    void handoffConflictKeepsRedisReservationForTtlFallback() {
        BackendCapacityReserveRequest request = startAllocatedFailover("skyblock-2");
        when(handoffService.registerAfterConnectionSuccess(request))
                .thenReturn(BackendCapacityHandoffRegistrationResult.PLAYER_BUSY);

        coordinator.completeSuccessfulConnection(PLAYER_ID, "skyblock-2");

        verify(handoffService).registerAfterConnectionSuccess(request);
        verify(releaseService, never()).releaseIfOwned(request);
    }

    @Test
    void disconnectBeforeConnectionReleasesExactReservation() {
        BackendCapacityReserveRequest request = startAllocatedFailover("skyblock-2");

        coordinator.cancelPendingFailover(PLAYER_ID);

        assertFalse(failoverRegistry.isReserved(PLAYER_ID));
        verify(releaseService).releaseIfOwned(request);
    }

    @Test
    void allocationFailureDisconnectsAndClearsPendingMarker() {
        when(allocationService.allocate(
                eq(player),
                any(UUID.class),
                eq(BackendType.SKYBLOCK),
                eq(EXCLUSIONS)
        )).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("redis unavailable")
        ));

        BackendKickFailoverResolution resolution = resolve(BackendType.SKYBLOCK);

        assertEquals(BackendKickFailoverResolutionStatus.DISCONNECT, resolution.status());
        assertFalse(failoverRegistry.isReserved(PLAYER_ID));
    }

    private BackendCapacityReserveRequest startAllocatedFailover(String backendName) {
        RegisteredServer target = server(backendName);
        BackendCapacityReserveRequest request = request(backendName);
        when(allocationService.allocate(
                eq(player),
                any(UUID.class),
                eq(BackendType.SKYBLOCK),
                eq(EXCLUSIONS)
        )).thenReturn(CompletableFuture.completedFuture(
                allocated(target, request)
        ));

        BackendKickFailoverResolution resolution = resolve(BackendType.SKYBLOCK);
        assertEquals(BackendKickFailoverResolutionStatus.REDIRECT, resolution.status());
        return request;
    }

    private BackendKickFailoverResolution resolve(BackendType sourceType) {
        return coordinator.resolve(
                player,
                sourceType,
                EXCLUSIONS,
                REASON
        ).toCompletableFuture().join();
    }

    private DistributedResolvedTargetAllocation allocated(
            RegisteredServer target,
            BackendCapacityReserveRequest request
    ) {
        return DistributedResolvedTargetAllocation.allocated(
                TransferTargetResolution.resolved(target),
                request,
                BackendCapacityReserveResult.Status.RESERVED
        );
    }

    private BackendCapacityReserveRequest request(String backendName) {
        return new BackendCapacityReserveRequest(
                new BackendCapacityReservation(
                        UUID.randomUUID(),
                        PLAYER_ID,
                        backendName
                ),
                new PlayerSessionLease(
                        new AuthenticatedPlayerSession(
                                PLAYER_ID,
                                "HarriOcho",
                                1_750_000_000_000L
                        ),
                        new ProxyInstanceIdentity(
                                "proxy-1",
                                UUID.randomUUID()
                        ),
                        7L
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
}

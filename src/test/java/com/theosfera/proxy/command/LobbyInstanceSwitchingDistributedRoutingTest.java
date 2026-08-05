package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.BackendOccupancyReadResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.BackendCapacityHandoffRegistrationResult;
import com.theosfera.proxy.transfer.BackendCapacityHandoffService;
import com.theosfera.proxy.transfer.BackendTargetCandidate;
import com.theosfera.proxy.transfer.DistributedBackendCapacityReleaseService;
import com.theosfera.proxy.transfer.DistributedPlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.DistributedPlayerTransferTargetAllocationService;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.TransferTargetCandidates;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyInstanceSwitchingDistributedRoutingTest {

    private static final UUID PLAYER_ID = UUID.fromString(
            "417e98b4-74a1-467e-b453-a15be3af8996"
    );
    private static final UUID REQUEST_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );
    private static final UUID INCARNATION_ID = UUID.fromString(
            "99999999-8888-7777-6666-555555555555"
    );
    private static final long NOW = 1_750_000_000_000L;

    @Test
    void switchExcludesCurrentLobbyAndRoutesThroughDistributedCapacity() {
        AuthenticatedPlayerSessionRegistry sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();
        BackendIdentityRegistry identityRegistry =
                new BackendIdentityRegistry();
        PendingPlayerTransferRegistry transferRegistry =
                new PendingPlayerTransferRegistry();
        BackendBootstrapRegistry bootstrapRegistry =
                new BackendBootstrapRegistry();

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getCurrentServer()).thenReturn(
                Optional.of(serverConnection("lobby-1"))
        );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 1_000L
                );
        sessionRegistry.register(session);
        identityRegistry.register(
                new BackendIdentity("lobby-1", BackendType.LOBBY)
        );

        PlayerSessionLease lease = new PlayerSessionLease(
                session,
                new ProxyInstanceIdentity("proxy-1", INCARNATION_ID),
                7L
        );
        PlayerSessionLeaseBindingRegistry sessionLeaseBindings =
                mock(PlayerSessionLeaseBindingRegistry.class);
        when(sessionLeaseBindings.find(player)).thenReturn(Optional.of(lease));

        RegisteredServer lobby2 = server("lobby-2");
        BackendTargetCandidate lobby2Candidate = new BackendTargetCandidate(
                "lobby-2",
                lobby2,
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        100,
                        80
                )
        );

        TransferTargetResolver resolver = mock(TransferTargetResolver.class);
        when(resolver.candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(
                TransferTargetCandidates.configured(
                        List.of(lobby2Candidate),
                        List.of()
                )
        );

        BackendOccupancyCoordinator occupancyCoordinator =
                mock(BackendOccupancyCoordinator.class);
        when(occupancyCoordinator.read("lobby-2"))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendOccupancyReadResult.available(12)
                ));

        BackendCapacityCoordinator capacityCoordinator =
                mock(BackendCapacityCoordinator.class);
        when(capacityCoordinator.reservedCount("lobby-2"))
                .thenReturn(CompletableFuture.completedFuture(0));
        when(capacityCoordinator.reserve(any(), eq(100)))
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

        DistributedPlayerTransferTargetAllocationService allocationService =
                new DistributedPlayerTransferTargetAllocationService(
                        resolver,
                        transferRegistry,
                        sessionLeaseBindings,
                        occupancyCoordinator,
                        capacityCoordinator
                );

        PlayerTransferExecutor transferExecutor =
                mock(PlayerTransferExecutor.class);
        when(transferExecutor.execute(player, lobby2))
                .thenReturn(CompletableFuture.completedFuture(
                        PlayerTransferCompletion.success()
                ));

        DistributedBackendCapacityReleaseService releaseService =
                mock(DistributedBackendCapacityReleaseService.class);
        BackendCapacityHandoffService handoffService =
                mock(BackendCapacityHandoffService.class);
        when(handoffService.registerAfterConnectionSuccess(any()))
                .thenReturn(
                        BackendCapacityHandoffRegistrationResult.REGISTERED
                );

        DistributedPlayerTransferRetryCoordinator retryCoordinator =
                new DistributedPlayerTransferRetryCoordinator(
                        bootstrapRegistry,
                        transferRegistry,
                        allocationService,
                        transferExecutor,
                        releaseService,
                        handoffService,
                        mock(Logger.class)
                );

        LobbyTransferService service = new LobbyTransferService(
                sessionRegistry,
                identityRegistry,
                retryCoordinator,
                Clock.fixed(
                        Instant.ofEpochMilli(NOW),
                        ZoneOffset.UTC
                ),
                () -> REQUEST_ID
        );

        service.switchLobbyInstance(player);

        verify(resolver).candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
        verify(resolver, never()).candidates(
                BackendType.LOBBY,
                Set.of()
        );
        verify(transferExecutor).execute(player, lobby2);

        ArgumentCaptor<BackendCapacityReserveRequest> capacityRequestCaptor =
                ArgumentCaptor.forClass(
                        BackendCapacityReserveRequest.class
                );
        verify(capacityCoordinator).reserve(
                capacityRequestCaptor.capture(),
                eq(100)
        );

        BackendCapacityReserveRequest capacityRequest =
                capacityRequestCaptor.getValue();
        assertSame(lease, capacityRequest.sessionLease());
        assertEquals(
                REQUEST_ID,
                capacityRequest.reservation().requestId()
        );
        assertEquals(
                PLAYER_ID,
                capacityRequest.reservation().playerId()
        );
        assertEquals(
                "lobby-2",
                capacityRequest.reservation().backendName()
        );

        verify(handoffService).registerAfterConnectionSuccess(
                capacityRequest
        );
        verify(releaseService, never()).releaseIfOwned(any());
        verify(player).sendMessage(
                LobbyTransferService.SWITCH_SUCCESS_MESSAGE
        );
        assertTrue(transferRegistry.snapshotByPlayer().isEmpty());
    }

    private ServerConnection serverConnection(String serverName) {
        ServerConnection connection = mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);
        when(connection.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(serverName);
        return connection;
    }

    private RegisteredServer server(String serverName) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo serverInfo = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(serverName);
        return server;
    }
}

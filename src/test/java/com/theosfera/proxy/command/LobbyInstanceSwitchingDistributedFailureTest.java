package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.BackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.BackendOccupancyReadResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.BackendCapacityHandoffService;
import com.theosfera.proxy.transfer.BackendTargetCandidate;
import com.theosfera.proxy.transfer.DistributedBackendCapacityReleaseService;
import com.theosfera.proxy.transfer.DistributedPlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.DistributedPlayerTransferTargetAllocationService;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.TransferTargetCandidates;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyInstanceSwitchingDistributedFailureTest {

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
    void onlyCurrentLobbyFailsWithoutAllocationOrReconnect() {
        Fixture fixture = fixture();

        when(fixture.resolver().candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetCandidates.notConfigured());

        fixture.service().switchLobbyInstance(fixture.player());

        verify(fixture.resolver()).candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        );
        verify(fixture.resolver(), never()).candidates(
                BackendType.LOBBY,
                Set.of()
        );
        verify(fixture.capacityCoordinator(), never()).reserve(any(), anyInt());
        verify(fixture.transferExecutor(), never()).execute(any(), any());
        verify(fixture.handoffService(), never()).registerAfterConnectionSuccess(any());
        verify(fixture.releaseService(), never()).releaseIfOwned(any());
        verify(fixture.player()).sendMessage(
                LobbyTransferService.SWITCH_UNAVAILABLE_MESSAGE
        );
        assertTrue(fixture.transferRegistry().snapshotByPlayer().isEmpty());
    }

    @Test
    void alternativeWithoutCapacityDoesNotOvercommitOrReconnectCurrentLobby() {
        Fixture fixture = fixture();
        BackendTargetCandidate lobby2 = candidate("lobby-2");

        when(fixture.resolver().candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetCandidates.configured(
                List.of(lobby2),
                List.of()
        ));
        when(fixture.resolver().candidates(
                BackendType.LOBBY,
                Set.of("lobby-1", "lobby-2")
        )).thenReturn(TransferTargetCandidates.notConfigured());
        when(fixture.occupancyCoordinator().read("lobby-2"))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendOccupancyReadResult.available(99)
                ));
        when(fixture.capacityCoordinator().reservedCount("lobby-2"))
                .thenReturn(CompletableFuture.completedFuture(0));
        when(fixture.capacityCoordinator().reserve(any(), eq(100)))
                .thenReturn(CompletableFuture.completedFuture(
                        BackendCapacityReserveResult.withoutReservation(
                                BackendCapacityReserveResult.Status.NO_CAPACITY
                        )
                ));

        fixture.service().switchLobbyInstance(fixture.player());

        verify(fixture.capacityCoordinator()).reserve(any(), eq(100));
        verify(fixture.resolver()).candidates(
                BackendType.LOBBY,
                Set.of("lobby-1", "lobby-2")
        );
        verify(fixture.resolver(), never()).candidates(
                BackendType.LOBBY,
                Set.of()
        );
        verify(fixture.transferExecutor(), never()).execute(any(), any());
        verify(fixture.handoffService(), never()).registerAfterConnectionSuccess(any());
        verify(fixture.releaseService(), never()).releaseIfOwned(any());
        verify(fixture.player()).sendMessage(
                LobbyTransferService.SWITCH_UNAVAILABLE_MESSAGE
        );
        assertTrue(fixture.transferRegistry().snapshotByPlayer().isEmpty());
    }

    @Test
    void distributedLoadFailureFailsClosedWithoutLocalFallback() {
        Fixture fixture = fixture();
        BackendTargetCandidate lobby2 = candidate("lobby-2");

        when(fixture.resolver().candidates(
                BackendType.LOBBY,
                Set.of("lobby-1")
        )).thenReturn(TransferTargetCandidates.configured(
                List.of(lobby2),
                List.of()
        ));
        when(fixture.occupancyCoordinator().read("lobby-2"))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("redis unavailable")
                ));
        when(fixture.capacityCoordinator().reservedCount("lobby-2"))
                .thenReturn(CompletableFuture.completedFuture(0));

        fixture.service().switchLobbyInstance(fixture.player());

        verify(fixture.capacityCoordinator(), never()).reserve(any(), anyInt());
        verify(fixture.transferExecutor(), never()).execute(any(), any());
        verify(fixture.handoffService(), never()).registerAfterConnectionSuccess(any());
        verify(fixture.releaseService(), never()).releaseIfOwned(any());
        verify(fixture.resolver(), never()).candidates(
                BackendType.LOBBY,
                Set.of()
        );
        verify(fixture.player()).sendMessage(
                LobbyTransferService.SWITCH_UNAVAILABLE_MESSAGE
        );
        assertTrue(fixture.transferRegistry().snapshotByPlayer().isEmpty());
    }

    private Fixture fixture() {
        AuthenticatedPlayerSessionRegistry sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();
        BackendIdentityRegistry identityRegistry =
                new BackendIdentityRegistry();
        PendingPlayerTransferRegistry transferRegistry =
                new PendingPlayerTransferRegistry();
        BackendBootstrapRegistry bootstrapRegistry =
                new BackendBootstrapRegistry();

        Player player = mock(Player.class);
        ServerConnection lobby1Connection = serverConnection("lobby-1");
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getCurrentServer()).thenReturn(Optional.of(lobby1Connection));

        AuthenticatedPlayerSession session = new AuthenticatedPlayerSession(
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
        PlayerSessionLeaseBindingRegistry leaseBindings =
                mock(PlayerSessionLeaseBindingRegistry.class);
        when(leaseBindings.find(player)).thenReturn(Optional.of(lease));

        TransferTargetResolver resolver = mock(TransferTargetResolver.class);
        BackendOccupancyCoordinator occupancyCoordinator =
                mock(BackendOccupancyCoordinator.class);
        BackendCapacityCoordinator capacityCoordinator =
                mock(BackendCapacityCoordinator.class);
        PlayerTransferExecutor transferExecutor =
                mock(PlayerTransferExecutor.class);
        DistributedBackendCapacityReleaseService releaseService =
                mock(DistributedBackendCapacityReleaseService.class);
        BackendCapacityHandoffService handoffService =
                mock(BackendCapacityHandoffService.class);

        DistributedPlayerTransferTargetAllocationService allocationService =
                new DistributedPlayerTransferTargetAllocationService(
                        resolver,
                        transferRegistry,
                        leaseBindings,
                        occupancyCoordinator,
                        capacityCoordinator
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

        return new Fixture(
                player,
                transferRegistry,
                resolver,
                occupancyCoordinator,
                capacityCoordinator,
                transferExecutor,
                releaseService,
                handoffService,
                service
        );
    }

    private BackendTargetCandidate candidate(String serverName) {
        RegisteredServer server = server(serverName);
        return new BackendTargetCandidate(
                serverName,
                server,
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        100,
                        80
                )
        );
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

    private record Fixture(
            Player player,
            PendingPlayerTransferRegistry transferRegistry,
            TransferTargetResolver resolver,
            BackendOccupancyCoordinator occupancyCoordinator,
            BackendCapacityCoordinator capacityCoordinator,
            PlayerTransferExecutor transferExecutor,
            DistributedBackendCapacityReleaseService releaseService,
            BackendCapacityHandoffService handoffService,
            LobbyTransferService service
    ) {
    }
}

package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerShutdownPresenceOrderingTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    @Test
    void waitsForPresenceDrainBeforeSessionRelease() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        PlayerSessionLeaseBindingRegistry bindingRegistry =
                mock(PlayerSessionLeaseBindingRegistry.class);
        PlayerServerPresenceRegistry presenceRegistry =
                mock(PlayerServerPresenceRegistry.class);
        PlayerPresenceRuntimeService presenceRuntimeService =
                mock(PlayerPresenceRuntimeService.class);
        Logger logger = mock(Logger.class);
        Player player = mock(Player.class);
        PlayerSessionLease lease = lease();
        PlayerServerPresence presence = presence();
        CompletableFuture<PlayerPresenceRemoveResult> removal =
                new CompletableFuture<>();

        when(proxyServer.getAllPlayers()).thenReturn(List.of(player));
        when(bindingRegistry.find(player)).thenReturn(Optional.of(lease));
        when(presenceRegistry.find(PLAYER_ID))
                .thenReturn(Optional.of(presence));
        when(presenceRuntimeService.removeIfOwned(lease, presence))
                .thenReturn(removal);
        when(coordinator.releaseIfOwned(lease))
                .thenReturn(CompletableFuture.completedFuture(true));

        PlayerSessionShutdownReleaseService service =
                new PlayerSessionShutdownReleaseService(
                        proxyServer,
                        coordinator,
                        bindingRegistry,
                        presenceRegistry,
                        presenceRuntimeService,
                        logger
                );

        CompletableFuture<PlayerSessionShutdownReleaseService.ReleaseSummary>
                result = service.releaseBoundSessions()
                .toCompletableFuture();

        verify(bindingRegistry).clear();
        verify(presenceRuntimeService).removeIfOwned(lease, presence);
        verify(coordinator, never()).releaseIfOwned(lease);
        assertFalse(result.isDone());

        removal.complete(
                new PlayerPresenceRemoveResult(
                        PlayerPresenceRemoveResult.Status.REMOVED
                )
        );

        verify(coordinator).releaseIfOwned(lease);
        assertTrue(result.isDone());
        assertTrue(result.join().complete());
    }

    @Test
    void coordinationUnavailablePresenceStillFallsBackToSessionRelease() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        PlayerSessionLeaseBindingRegistry bindingRegistry =
                mock(PlayerSessionLeaseBindingRegistry.class);
        PlayerServerPresenceRegistry presenceRegistry =
                mock(PlayerServerPresenceRegistry.class);
        PlayerPresenceRuntimeService presenceRuntimeService =
                mock(PlayerPresenceRuntimeService.class);
        Logger logger = mock(Logger.class);
        Player player = mock(Player.class);
        PlayerSessionLease lease = lease();
        PlayerServerPresence presence = presence();

        when(proxyServer.getAllPlayers()).thenReturn(List.of(player));
        when(bindingRegistry.find(player)).thenReturn(Optional.of(lease));
        when(presenceRegistry.find(PLAYER_ID))
                .thenReturn(Optional.of(presence));
        when(presenceRuntimeService.removeIfOwned(lease, presence))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new PlayerPresenceRemoveResult(
                                        PlayerPresenceRemoveResult.Status
                                                .COORDINATION_UNAVAILABLE
                                )
                        )
                );
        when(coordinator.releaseIfOwned(lease))
                .thenReturn(CompletableFuture.completedFuture(true));

        PlayerSessionShutdownReleaseService service =
                new PlayerSessionShutdownReleaseService(
                        proxyServer,
                        coordinator,
                        bindingRegistry,
                        presenceRegistry,
                        presenceRuntimeService,
                        logger
                );

        PlayerSessionShutdownReleaseService.ReleaseSummary summary =
                service.releaseBoundSessions()
                        .toCompletableFuture()
                        .join();

        verify(presenceRuntimeService).removeIfOwned(lease, presence);
        verify(coordinator).releaseIfOwned(lease);
        assertTrue(summary.complete());
    }

    private static PlayerSessionLease lease() {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_000L
                ),
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.fromString(
                                "11111111-2222-3333-4444-555555555555"
                        )
                ),
                7L
        );
    }

    private static PlayerServerPresence presence() {
        return new PlayerServerPresence(
                PLAYER_ID,
                "lobby-1",
                2_000L
        );
    }
}

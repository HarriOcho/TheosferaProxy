package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerSessionShutdownReleaseServiceTest {

    @Test
    void releasesEveryBoundConnectedSession() throws Exception {
        ProxyServer proxyServer = mock(ProxyServer.class);
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        PlayerSessionLeaseBindingRegistry bindings =
                mock(PlayerSessionLeaseBindingRegistry.class);

        Player firstPlayer = player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Player secondPlayer = player("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        PlayerSessionLease firstLease = lease(firstPlayer, 11L);
        PlayerSessionLease secondLease = lease(secondPlayer, 12L);

        when(proxyServer.getAllPlayers())
                .thenReturn(List.of(firstPlayer, secondPlayer));
        when(bindings.find(firstPlayer)).thenReturn(Optional.of(firstLease));
        when(bindings.find(secondPlayer)).thenReturn(Optional.of(secondLease));
        when(coordinator.releaseIfOwned(firstLease))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(coordinator.releaseIfOwned(secondLease))
                .thenReturn(CompletableFuture.completedFuture(true));

        PlayerSessionShutdownReleaseService.ReleaseSummary summary =
                service(proxyServer, coordinator, bindings)
                        .releaseBoundSessions()
                        .toCompletableFuture()
                        .get(1, TimeUnit.SECONDS);

        assertEquals(2, summary.attempted());
        assertEquals(2, summary.released());
        assertTrue(summary.complete());

        InOrder shutdownOrder = inOrder(bindings, coordinator);
        shutdownOrder.verify(bindings).clear();
        shutdownOrder.verify(coordinator).releaseIfOwned(firstLease);
        shutdownOrder.verify(coordinator).releaseIfOwned(secondLease);
    }

    @Test
    void skipsPlayersWithoutBoundLeaseAndReportsFailedRelease()
            throws Exception {
        ProxyServer proxyServer = mock(ProxyServer.class);
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        PlayerSessionLeaseBindingRegistry bindings =
                mock(PlayerSessionLeaseBindingRegistry.class);

        Player boundPlayer = player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Player unboundPlayer = player("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        PlayerSessionLease boundLease = lease(boundPlayer, 21L);

        when(proxyServer.getAllPlayers())
                .thenReturn(List.of(boundPlayer, unboundPlayer));
        when(bindings.find(boundPlayer)).thenReturn(Optional.of(boundLease));
        when(bindings.find(unboundPlayer)).thenReturn(Optional.empty());
        when(coordinator.releaseIfOwned(boundLease))
                .thenReturn(CompletableFuture.completedFuture(false));

        PlayerSessionShutdownReleaseService.ReleaseSummary summary =
                service(proxyServer, coordinator, bindings)
                        .releaseBoundSessions()
                        .toCompletableFuture()
                        .get(1, TimeUnit.SECONDS);

        assertEquals(1, summary.attempted());
        assertEquals(0, summary.released());
        assertFalse(summary.complete());
        verify(bindings).clear();
    }

    @Test
    void releaseFailureDoesNotFailWholeDrain() throws Exception {
        ProxyServer proxyServer = mock(ProxyServer.class);
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        PlayerSessionLeaseBindingRegistry bindings =
                mock(PlayerSessionLeaseBindingRegistry.class);

        Player player = player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        PlayerSessionLease lease = lease(player, 31L);

        when(proxyServer.getAllPlayers()).thenReturn(List.of(player));
        when(bindings.find(player)).thenReturn(Optional.of(lease));
        when(coordinator.releaseIfOwned(lease))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("redis unavailable")
                ));

        PlayerSessionShutdownReleaseService.ReleaseSummary summary =
                service(proxyServer, coordinator, bindings)
                        .releaseBoundSessions()
                        .toCompletableFuture()
                        .get(1, TimeUnit.SECONDS);

        assertEquals(1, summary.attempted());
        assertEquals(0, summary.released());
        assertFalse(summary.complete());
        verify(bindings).clear();
    }

    private PlayerSessionShutdownReleaseService service(
            ProxyServer proxyServer,
            PlayerSessionCoordinator coordinator,
            PlayerSessionLeaseBindingRegistry bindings
    ) {
        return new PlayerSessionShutdownReleaseService(
                proxyServer,
                coordinator,
                bindings,
                mock(Logger.class)
        );
    }

    private Player player(String uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.fromString(uuid));
        return player;
    }

    private PlayerSessionLease lease(Player player, long token) {
        AuthenticatedPlayerSession session = new AuthenticatedPlayerSession(
                player.getUniqueId(),
                "HarriOcho",
                1_700_000_000_000L + token
        );
        return new PlayerSessionLease(
                session,
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.fromString(
                                "d505feca-365c-4fb4-818e-3efccf124d97"
                        )
                ),
                token
        );
    }
}

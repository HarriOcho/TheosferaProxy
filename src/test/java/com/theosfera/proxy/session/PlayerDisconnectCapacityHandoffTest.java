package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.BackendCapacityHandoffLifecycle;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.transfer.BackendCapacityReservationRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerDisconnectCapacityHandoffTest {

    @Test
    void waitsForExactCapacityHandoffReleaseBeforeSessionLeaseRelease() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        AuthenticatedPlayerSession session = session(playerId);
        PlayerSessionLease lease = lease(session);
        PlayerSessionLeaseBindingRegistry bindings =
                mock(PlayerSessionLeaseBindingRegistry.class);
        PlayerServerPresenceRegistry presences =
                mock(PlayerServerPresenceRegistry.class);
        PendingPlayerTransferRegistry transfers =
                mock(PendingPlayerTransferRegistry.class);
        AuthenticatedPlayerSessionRegistry sessions =
                mock(AuthenticatedPlayerSessionRegistry.class);
        PlayerSessionReleaseService releaseService =
                mock(PlayerSessionReleaseService.class);
        BackendCapacityHandoffLifecycle handoff =
                mock(BackendCapacityHandoffLifecycle.class);

        prepareLocalRemoval(
                player,
                playerId,
                session,
                lease,
                bindings,
                presences,
                transfers,
                sessions,
                Optional.empty()
        );

        CompletableFuture<Boolean> handoffRelease =
                new CompletableFuture<>();
        when(handoff.releaseForDisconnect(lease))
                .thenReturn(handoffRelease);

        PlayerDisconnectListener listener =
                new PlayerDisconnectListener(
                        bindings,
                        presences,
                        transfers,
                        sessions,
                        releaseService,
                        mock(Logger.class)
                );
        listener.configureCapacityHandoffLifecycle(handoff);

        listener.onDisconnect(disconnectEvent(player));

        verify(handoff).releaseForDisconnect(lease);
        verify(releaseService, never()).releaseIfUnbound(any(), any());

        handoffRelease.complete(true);

        verify(releaseService).releaseIfUnbound(
                eq(lease),
                any(PlayerSessionReleaseService.ReleaseCallbacks.class)
        );
    }

    @Test
    void removesPresenceThenHandoffThenSessionLease() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        AuthenticatedPlayerSession session = session(playerId);
        PlayerSessionLease lease = lease(session);
        PlayerServerPresence presence = new PlayerServerPresence(
                playerId,
                "lobby-1",
                2_000L
        );

        PlayerSessionLeaseBindingRegistry bindings =
                mock(PlayerSessionLeaseBindingRegistry.class);
        PlayerServerPresenceRegistry presences =
                mock(PlayerServerPresenceRegistry.class);
        PendingPlayerTransferRegistry transfers =
                mock(PendingPlayerTransferRegistry.class);
        AuthenticatedPlayerSessionRegistry sessions =
                mock(AuthenticatedPlayerSessionRegistry.class);
        PlayerSessionReleaseService releaseService =
                mock(PlayerSessionReleaseService.class);
        PlayerPresenceRuntimeService presenceRuntime =
                mock(PlayerPresenceRuntimeService.class);
        BackendCapacityHandoffLifecycle handoff =
                mock(BackendCapacityHandoffLifecycle.class);

        prepareLocalRemoval(
                player,
                playerId,
                session,
                lease,
                bindings,
                presences,
                transfers,
                sessions,
                Optional.of(presence)
        );

        CompletableFuture<PlayerPresenceRemoveResult> presenceRemoval =
                new CompletableFuture<>();
        when(presenceRuntime.removeIfOwned(lease, presence))
                .thenReturn(presenceRemoval);

        CompletableFuture<Boolean> handoffRelease =
                new CompletableFuture<>();
        when(handoff.releaseForDisconnect(lease))
                .thenReturn(handoffRelease);

        PlayerDisconnectListener listener =
                new PlayerDisconnectListener(
                        bindings,
                        presences,
                        transfers,
                        new BackendCapacityReservationRegistry(),
                        sessions,
                        releaseService,
                        presenceRuntime,
                        mock(Logger.class)
                );
        listener.configureCapacityHandoffLifecycle(handoff);

        listener.onDisconnect(disconnectEvent(player));

        verify(presenceRuntime).removeIfOwned(lease, presence);
        verify(handoff, never()).releaseForDisconnect(any());
        verify(releaseService, never()).releaseIfUnbound(any(), any());

        presenceRemoval.complete(
                new PlayerPresenceRemoveResult(
                        PlayerPresenceRemoveResult.Status.REMOVED
                )
        );

        verify(handoff).releaseForDisconnect(lease);
        verify(releaseService, never()).releaseIfUnbound(any(), any());

        handoffRelease.complete(true);

        verify(releaseService).releaseIfUnbound(
                eq(lease),
                any(PlayerSessionReleaseService.ReleaseCallbacks.class)
        );
    }

    private void prepareLocalRemoval(
            Player player,
            UUID playerId,
            AuthenticatedPlayerSession session,
            PlayerSessionLease lease,
            PlayerSessionLeaseBindingRegistry bindings,
            PlayerServerPresenceRegistry presences,
            PendingPlayerTransferRegistry transfers,
            AuthenticatedPlayerSessionRegistry sessions,
            Optional<PlayerServerPresence> presence
    ) {
        when(transfers.removeByPlayer(playerId))
                .thenReturn(Optional.empty());
        when(presences.remove(playerId)).thenReturn(presence);
        when(bindings.find(player)).thenReturn(Optional.of(lease));
        when(bindings.removeForDisconnect(player))
                .thenReturn(Optional.of(lease));
        when(sessions.removeIfMatches(session))
                .thenReturn(Optional.of(session));
    }

    private AuthenticatedPlayerSession session(UUID playerId) {
        return new AuthenticatedPlayerSession(
                playerId,
                "HarriOcho",
                1_000L
        );
    }

    private PlayerSessionLease lease(
            AuthenticatedPlayerSession session
    ) {
        return new PlayerSessionLease(
                session,
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.randomUUID()
                ),
                7L
        );
    }

    private DisconnectEvent disconnectEvent(Player player) {
        DisconnectEvent event = mock(DisconnectEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }
}

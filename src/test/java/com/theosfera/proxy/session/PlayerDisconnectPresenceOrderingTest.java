package com.theosfera.proxy.session;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerDisconnectPresenceOrderingTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    @Test
    void waitsForPresenceRemovalBeforeSessionRelease() {
        PlayerSessionLeaseBindingRegistry bindingRegistry =
                mock(PlayerSessionLeaseBindingRegistry.class);
        PlayerServerPresenceRegistry presenceRegistry =
                mock(PlayerServerPresenceRegistry.class);
        PendingPlayerTransferRegistry transferRegistry =
                mock(PendingPlayerTransferRegistry.class);
        BackendCapacityReservationRegistry capacityRegistry =
                mock(BackendCapacityReservationRegistry.class);
        AuthenticatedPlayerSessionRegistry sessionRegistry =
                mock(AuthenticatedPlayerSessionRegistry.class);
        PlayerSessionReleaseService releaseService =
                mock(PlayerSessionReleaseService.class);
        PlayerPresenceRuntimeService presenceRuntimeService =
                mock(PlayerPresenceRuntimeService.class);
        Logger logger = mock(Logger.class);
        Player player = mock(Player.class);
        DisconnectEvent event = mock(DisconnectEvent.class);
        PlayerSessionLease lease = lease();
        PlayerServerPresence presence = presence();
        CompletableFuture<PlayerPresenceRemoveResult> removal =
                new CompletableFuture<>();

        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(transferRegistry.removeByPlayer(PLAYER_ID))
                .thenReturn(Optional.empty());
        when(presenceRegistry.remove(PLAYER_ID))
                .thenReturn(Optional.of(presence));
        when(bindingRegistry.find(player))
                .thenReturn(Optional.of(lease));
        when(bindingRegistry.removeForDisconnect(player))
                .thenReturn(Optional.of(lease));
        when(sessionRegistry.removeIfMatches(lease.session()))
                .thenReturn(Optional.of(lease.session()));
        when(presenceRuntimeService.removeIfOwned(lease, presence))
                .thenReturn(removal);

        PlayerDisconnectListener listener =
                new PlayerDisconnectListener(
                        bindingRegistry,
                        presenceRegistry,
                        transferRegistry,
                        capacityRegistry,
                        sessionRegistry,
                        releaseService,
                        presenceRuntimeService,
                        logger
                );

        listener.onDisconnect(event);

        verify(presenceRuntimeService).removeIfOwned(lease, presence);
        verify(releaseService, never()).releaseIfUnbound(
                any(PlayerSessionLease.class),
                any(PlayerSessionReleaseService.ReleaseCallbacks.class)
        );

        removal.complete(
                new PlayerPresenceRemoveResult(
                        PlayerPresenceRemoveResult.Status.REMOVED
                )
        );

        verify(releaseService).releaseIfUnbound(
                any(PlayerSessionLease.class),
                any(PlayerSessionReleaseService.ReleaseCallbacks.class)
        );
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

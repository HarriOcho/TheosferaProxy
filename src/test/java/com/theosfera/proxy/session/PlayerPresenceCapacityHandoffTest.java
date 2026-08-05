package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.BackendCapacityHandoffLifecycle;
import com.theosfera.proxy.coordination.PlayerPresenceCoordinator;
import com.theosfera.proxy.coordination.PlayerPresencePublishRequest;
import com.theosfera.proxy.coordination.PlayerPresencePublishResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerPresenceCapacityHandoffTest {

    @Test
    void onlyConfirmedDistributedPresenceClosesHandoffAndLaterSuccessCanRecover() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        PlayerSessionLease lease = new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        playerId,
                        "HarriOcho",
                        1_000L
                ),
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.randomUUID()
                ),
                7L
        );
        PlayerServerPresence presence = new PlayerServerPresence(
                playerId,
                "lobby-1",
                2_000L
        );

        PlayerPresenceCoordinator coordinator =
                mock(PlayerPresenceCoordinator.class);
        PlayerSessionLeaseBindingRegistry bindings =
                mock(PlayerSessionLeaseBindingRegistry.class);
        PlayerServerPresenceRegistry localRegistry =
                mock(PlayerServerPresenceRegistry.class);
        BackendCapacityHandoffLifecycle handoff =
                mock(BackendCapacityHandoffLifecycle.class);

        when(localRegistry.update(presence)).thenReturn(
                PlayerPresenceUpdateResult.RECORDED,
                PlayerPresenceUpdateResult.ALREADY_RECORDED
        );
        when(bindings.find(player)).thenReturn(Optional.of(lease));

        AtomicInteger publishes = new AtomicInteger();
        when(coordinator.publish(any())).thenAnswer(invocation -> {
            PlayerPresencePublishRequest request = invocation.getArgument(
                    0,
                    PlayerPresencePublishRequest.class
            );
            if (publishes.getAndIncrement() == 0) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        PlayerPresencePublishResult.withoutPresence(
                                PlayerPresencePublishResult.Status
                                        .COORDINATION_UNAVAILABLE
                        )
                );
            }
            return java.util.concurrent.CompletableFuture.completedFuture(
                    PlayerPresencePublishResult.withPresence(
                            PlayerPresencePublishResult.Status
                                    .ALREADY_RECORDED,
                            request.presence()
                    )
            );
        });

        PlayerPresenceRuntimeService service =
                new PlayerPresenceRuntimeService(
                        mock(ProxyServer.class),
                        coordinator,
                        bindings,
                        localRegistry,
                        mock(PlayerPresenceRenewalScheduler.class),
                        Duration.ofSeconds(10),
                        mock(Logger.class)
                );
        service.configureCapacityHandoffLifecycle(handoff);

        service.publishReady(player, presence);
        verify(handoff, never()).onPresenceConfirmed(any(), any());

        service.publishReady(player, presence);
        verify(handoff).onPresenceConfirmed(lease, "lobby-1");
    }
}

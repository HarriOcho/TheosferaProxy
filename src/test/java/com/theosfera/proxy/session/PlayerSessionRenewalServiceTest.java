package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionRenewResult;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerSessionRenewalServiceTest {

    private final UUID playerId = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );
    private final AuthenticatedPlayerSession session =
            new AuthenticatedPlayerSession(
                    playerId,
                    "HarriOcho",
                    Instant.parse("2026-08-02T20:00:00Z").toEpochMilli()
            );
    private final PlayerSessionLease lease = new PlayerSessionLease(
            session,
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString(
                            "11111111-2222-3333-4444-555555555555"
                    )
            ),
            7L
    );

    private ProxyServer proxyServer;
    private Player player;
    private PlayerSessionCoordinator coordinator;
    private PlayerSessionLeaseBindingRegistry bindingRegistry;
    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private CapturingScheduler scheduler;
    private MutableClock clock;
    private PlayerSessionRenewalService service;

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);
        player = mock(Player.class);
        coordinator = mock(PlayerSessionCoordinator.class);
        bindingRegistry = mock(PlayerSessionLeaseBindingRegistry.class);
        sessionRegistry = mock(AuthenticatedPlayerSessionRegistry.class);
        scheduler = new CapturingScheduler();
        clock = new MutableClock(1_000L);

        when(sessionRegistry.snapshot()).thenReturn(Map.of(playerId, session));
        when(proxyServer.getPlayer(playerId)).thenReturn(Optional.of(player));
        when(bindingRegistry.find(player)).thenReturn(Optional.of(lease));
        when(bindingRegistry.removeIfMatches(player, lease))
                .thenReturn(Optional.of(lease));
        when(sessionRegistry.removeIfMatches(session))
                .thenReturn(Optional.of(session));

        service = new PlayerSessionRenewalService(
                proxyServer,
                coordinator,
                bindingRegistry,
                sessionRegistry,
                scheduler,
                clock,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                mock(Logger.class)
        );
        service.start();
    }

    @Test
    void successfulRenewKeepsExactBindingActive() {
        when(coordinator.renew(lease)).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionRenewResult.renewed(lease)
                )
        );

        scheduler.run();

        verify(coordinator).renew(lease);
        verify(bindingRegistry, never()).removeIfMatches(player, lease);
        verify(player, never()).disconnect(any(Component.class));
    }

    @Test
    void terminalOwnershipLossRevokesAndDisconnects() {
        when(coordinator.renew(lease)).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionRenewResult.withoutLease(
                                PlayerSessionRenewResult.Status.NOT_OWNER
                        )
                )
        );

        scheduler.run();

        verify(bindingRegistry).removeIfMatches(player, lease);
        verify(sessionRegistry).removeIfMatches(session);
        verify(player).disconnect(any(Component.class));
    }

    @Test
    void firstUnconfirmedCoordinationFailureFailsClosed() {
        when(coordinator.renew(lease)).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionRenewResult.withoutLease(
                                PlayerSessionRenewResult.Status
                                        .COORDINATION_UNAVAILABLE
                        )
                )
        );

        scheduler.run();

        verify(bindingRegistry).removeIfMatches(player, lease);
        verify(sessionRegistry).removeIfMatches(session);
        verify(player).disconnect(any(Component.class));
    }

    @Test
    void confirmedLeaseSurvivesTransientFailureUntilDeadline() {
        when(coordinator.renew(lease))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerSessionRenewResult.renewed(lease)
                        )
                )
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerSessionRenewResult.withoutLease(
                                        PlayerSessionRenewResult.Status
                                                .COORDINATION_UNAVAILABLE
                                )
                        )
                );

        scheduler.run();
        clock.advance(Duration.ofSeconds(10));
        scheduler.run();

        verify(bindingRegistry, never()).removeIfMatches(player, lease);
        verify(player, never()).disconnect(any(Component.class));

        clock.advance(Duration.ofSeconds(21));
        scheduler.run();

        verify(bindingRegistry).removeIfMatches(player, lease);
        verify(sessionRegistry).removeIfMatches(session);
        verify(player).disconnect(any(Component.class));
    }

    @Test
    void lateCompletionCannotRevokeReplacementBinding() {
        CompletableFuture<PlayerSessionRenewResult> pending =
                new CompletableFuture<>();
        when(coordinator.renew(lease)).thenReturn(pending);

        scheduler.run();
        when(bindingRegistry.find(player)).thenReturn(Optional.empty());

        pending.complete(
                PlayerSessionRenewResult.withoutLease(
                        PlayerSessionRenewResult.Status.NOT_OWNER
                )
        );

        verify(bindingRegistry, never()).removeIfMatches(player, lease);
        verify(sessionRegistry, never()).removeIfMatches(session);
        verify(player, never()).disconnect(any(Component.class));
    }

    private static final class CapturingScheduler
            implements PlayerSessionRenewalScheduler {

        private Runnable task;

        @Override
        public Handle schedule(Runnable task, Duration interval) {
            this.task = task;
            return () -> this.task = null;
        }

        void run() {
            if (task != null) {
                task.run();
            }
        }
    }

    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}

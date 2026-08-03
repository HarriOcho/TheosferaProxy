package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.DistributedPlayerPresence;
import com.theosfera.proxy.coordination.PlayerPresenceCoordinator;
import com.theosfera.proxy.coordination.PlayerPresencePublishRequest;
import com.theosfera.proxy.coordination.PlayerPresencePublishResult;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveRequest;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerPresenceRuntimeServiceTest {

    private static final UUID PLAYER_ID = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );
    private static final UUID INCARNATION_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );
    private static final long AUTHENTICATED_AT = 1_000L;
    private static final long READY_AT = 2_000L;
    private static final long FENCING_TOKEN = 7L;
    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(10);

    private ProxyServer proxyServer;
    private PlayerPresenceCoordinator coordinator;
    private PlayerSessionLeaseBindingRegistry bindingRegistry;
    private PlayerServerPresenceRegistry localRegistry;
    private PlayerPresenceRenewalScheduler scheduler;
    private Logger logger;
    private Player player;
    private PlayerSessionLease lease;
    private PlayerServerPresence presence;
    private PlayerPresenceRuntimeService service;

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);
        coordinator = mock(PlayerPresenceCoordinator.class);
        bindingRegistry = mock(PlayerSessionLeaseBindingRegistry.class);
        localRegistry = mock(PlayerServerPresenceRegistry.class);
        scheduler = mock(PlayerPresenceRenewalScheduler.class);
        logger = mock(Logger.class);
        player = mock(Player.class);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        AuthenticatedPlayerSession session = new AuthenticatedPlayerSession(
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        lease = new PlayerSessionLease(
                session,
                new ProxyInstanceIdentity("proxy-1", INCARNATION_ID),
                FENCING_TOKEN
        );

        presence = new PlayerServerPresence(
                PLAYER_ID,
                "lobby-1",
                READY_AT
        );

        service = new PlayerPresenceRuntimeService(
                proxyServer,
                coordinator,
                bindingRegistry,
                localRegistry,
                scheduler,
                RENEW_INTERVAL,
                logger
        );
    }

    @Test
    void startsRenewalOnceAndCancelsItOnStop() {
        AtomicBoolean cancelled = new AtomicBoolean();
        PlayerPresenceRenewalScheduler.Handle handle =
                () -> cancelled.set(true);

        when(scheduler.schedule(any(), any())).thenReturn(handle);

        service.start();

        verify(scheduler).schedule(any(), RENEW_INTERVAL);
        assertThrows(IllegalStateException.class, service::start);

        service.stop();

        assertTrue(cancelled.get());
    }

    @Test
    void publishReadyUsesExactBoundLeaseAndPresenceFenceData() {
        when(localRegistry.update(presence))
                .thenReturn(PlayerPresenceUpdateResult.RECORDED);
        when(bindingRegistry.find(player)).thenReturn(Optional.of(lease));
        when(coordinator.publish(any())).thenAnswer(invocation -> {
            PlayerPresencePublishRequest request = invocation.getArgument(
                    0,
                    PlayerPresencePublishRequest.class
            );
            DistributedPlayerPresence published = request.presence();
            return CompletableFuture.completedFuture(
                    PlayerPresencePublishResult.withPresence(
                            PlayerPresencePublishResult.Status.RECORDED,
                            published
                    )
            );
        });

        PlayerPresenceUpdateResult result = service.publishReady(
                player,
                presence
        );

        assertEquals(PlayerPresenceUpdateResult.RECORDED, result);

        ArgumentCaptor<PlayerPresencePublishRequest> captor =
                ArgumentCaptor.forClass(PlayerPresencePublishRequest.class);
        verify(coordinator).publish(captor.capture());

        PlayerPresencePublishRequest request = captor.getValue();
        assertSame(lease, request.sessionLease());
        assertEquals("lobby-1", request.backendName());
        assertEquals(READY_AT, request.sequence());
        assertEquals(READY_AT, request.observedAt());
        assertEquals(
                FENCING_TOKEN,
                request.presence().sessionFencingToken()
        );
        assertEquals(lease.owner(), request.presence().owner());
    }

    @Test
    void publishReadyKeepsLocalPresenceWhenLeaseIsMissing() {
        when(localRegistry.update(presence))
                .thenReturn(PlayerPresenceUpdateResult.RECORDED);
        when(bindingRegistry.find(player)).thenReturn(Optional.empty());

        PlayerPresenceUpdateResult result = service.publishReady(
                player,
                presence
        );

        assertEquals(PlayerPresenceUpdateResult.RECORDED, result);
        verify(coordinator, never()).publish(any());
        verify(logger).warn(
                "Presencia local para {} no se publico en Redis porque no existe un lease de sesion vinculado.",
                PLAYER_ID
        );
    }

    @Test
    void rejectedLocalPresenceIsNeverPublishedDistributed() {
        when(localRegistry.update(presence))
                .thenReturn(PlayerPresenceUpdateResult.STALE);

        PlayerPresenceUpdateResult result = service.publishReady(
                player,
                presence
        );

        assertEquals(PlayerPresenceUpdateResult.STALE, result);
        verify(bindingRegistry, never()).find(any(Player.class));
        verify(coordinator, never()).publish(any());
    }

    @Test
    void renewalPublishesSnapshotWithCurrentExactBinding() {
        AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        PlayerPresenceRenewalScheduler.Handle handle = () -> {
        };

        when(scheduler.schedule(any(), any())).thenAnswer(invocation -> {
            scheduledTask.set(invocation.getArgument(0, Runnable.class));
            return handle;
        });
        when(localRegistry.snapshot())
                .thenReturn(Map.of(PLAYER_ID, presence));
        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.of(player));
        when(bindingRegistry.find(player)).thenReturn(Optional.of(lease));
        when(coordinator.publish(any())).thenAnswer(invocation -> {
            PlayerPresencePublishRequest request = invocation.getArgument(
                    0,
                    PlayerPresencePublishRequest.class
            );
            return CompletableFuture.completedFuture(
                    PlayerPresencePublishResult.withPresence(
                            PlayerPresencePublishResult.Status.ALREADY_RECORDED,
                            request.presence()
                    )
            );
        });

        service.start();
        scheduledTask.get().run();

        ArgumentCaptor<PlayerPresencePublishRequest> captor =
                ArgumentCaptor.forClass(PlayerPresencePublishRequest.class);
        verify(coordinator).publish(captor.capture());
        assertSame(lease, captor.getValue().sessionLease());
        assertEquals(
                FENCING_TOKEN,
                captor.getValue().presence().sessionFencingToken()
        );
    }

    @Test
    void renewalSkipsDisconnectedPlayersAndMissingBindings() {
        UUID otherPlayerId = UUID.fromString(
                "99999999-8888-7777-6666-555555555555"
        );
        Player otherPlayer = mock(Player.class);
        PlayerServerPresence otherPresence = new PlayerServerPresence(
                otherPlayerId,
                "skyblock-1",
                READY_AT + 1
        );

        AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        PlayerPresenceRenewalScheduler.Handle handle = () -> {
        };

        when(scheduler.schedule(any(), any())).thenAnswer(invocation -> {
            scheduledTask.set(invocation.getArgument(0, Runnable.class));
            return handle;
        });
        when(localRegistry.snapshot()).thenReturn(
                Map.of(
                        PLAYER_ID,
                        presence,
                        otherPlayerId,
                        otherPresence
                )
        );
        when(proxyServer.getPlayer(PLAYER_ID)).thenReturn(Optional.empty());
        when(proxyServer.getPlayer(otherPlayerId))
                .thenReturn(Optional.of(otherPlayer));
        when(bindingRegistry.find(otherPlayer)).thenReturn(Optional.empty());

        service.start();
        scheduledTask.get().run();

        verify(coordinator, never()).publish(any());
    }

    @Test
    void staleAsyncPublishCallbackDoesNotMutateLocalPresence() {
        CompletableFuture<PlayerPresencePublishResult> pending =
                new CompletableFuture<>();

        when(localRegistry.update(presence))
                .thenReturn(PlayerPresenceUpdateResult.RECORDED);
        when(bindingRegistry.find(player)).thenReturn(Optional.of(lease));
        when(coordinator.publish(any())).thenReturn(pending);

        service.publishReady(player, presence);

        pending.complete(
                PlayerPresencePublishResult.withoutPresence(
                        PlayerPresencePublishResult.Status.STALE
                )
        );

        verify(localRegistry).update(presence);
        verify(logger).warn(
                "Presencia Redis rechazada para {} por {}.",
                PLAYER_ID,
                PlayerPresencePublishResult.Status.STALE
        );
    }

    @Test
    void removeIfOwnedUsesExactLeaseBackendAndReadySequence() {
        PlayerPresenceRemoveResult removed = new PlayerPresenceRemoveResult(
                PlayerPresenceRemoveResult.Status.REMOVED
        );
        CompletionStage<PlayerPresenceRemoveResult> completion =
                CompletableFuture.completedFuture(removed);

        when(coordinator.removeIfOwned(any())).thenReturn(completion);

        CompletionStage<PlayerPresenceRemoveResult> result =
                service.removeIfOwned(lease, presence);

        assertSame(completion, result);

        ArgumentCaptor<PlayerPresenceRemoveRequest> captor =
                ArgumentCaptor.forClass(PlayerPresenceRemoveRequest.class);
        verify(coordinator).removeIfOwned(captor.capture());

        PlayerPresenceRemoveRequest request = captor.getValue();
        assertSame(lease, request.sessionLease());
        assertEquals("lobby-1", request.backendName());
        assertEquals(READY_AT, request.sequence());
    }

    @Test
    void rejectsMismatchedPlayerAndLeaseIdentities() {
        Player wrongPlayer = mock(Player.class);
        when(wrongPlayer.getUniqueId()).thenReturn(UUID.randomUUID());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.publishReady(wrongPlayer, presence)
        );

        PlayerServerPresence wrongPresence = new PlayerServerPresence(
                UUID.randomUUID(),
                "lobby-1",
                READY_AT
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.removeIfOwned(lease, wrongPresence)
        );
    }

    @Test
    void rejectsNonPositiveRenewInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerPresenceRuntimeService(
                        proxyServer,
                        coordinator,
                        bindingRegistry,
                        localRegistry,
                        scheduler,
                        Duration.ZERO,
                        logger
                )
        );
    }
}

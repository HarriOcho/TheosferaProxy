package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.local.LocalPlayerSessionCoordinator;
import com.theosfera.proxy.transfer.BackendCapacityReservationRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerDisconnectListenerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final UUID ACQUISITION_ID =
            UUID.fromString(
                    "22222222-3333-4444-5555-666666666666"
            );

    private static final ProxyInstanceIdentity PROXY_IDENTITY =
            new ProxyInstanceIdentity(
                    "proxy-disconnect-test",
                    UUID.fromString(
                            "09989199-f70d-4a0a-b442-0efd5aed14ef"
                    )
            );

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerSessionCoordinator sessionCoordinator;
    private PlayerSessionLeaseBindingRegistry leaseBindingRegistry;
    private PlayerServerPresenceRegistry presenceRegistry;
    private PendingPlayerTransferRegistry transferRegistry;
    private Logger logger;
    private Player player;
    private PlayerDisconnectListener listener;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        sessionCoordinator =
                new LocalPlayerSessionCoordinator(
                        sessionRegistry
                );

        leaseBindingRegistry =
                new PlayerSessionLeaseBindingRegistry();

        presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );

        transferRegistry =
                new PendingPlayerTransferRegistry();

        logger = mock(Logger.class);
        player = player(PLAYER_ID);

        listener = new PlayerDisconnectListener(
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                releaseService(
                        sessionCoordinator,
                        new ManualPlayerSessionReleaseTimeoutScheduler()
                ),
                logger
        );
    }

    @Test
    void removesLeaseSessionPresenceAndTransfer() {
        PlayerSessionLease lease =
                registerSessionLease(player);

        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "lobby-1",
                        2_000L
                )
        );

        transferRegistry.register(
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1",
                        "skyblock-1",
                        3_000L
                )
        );

        listener.onDisconnect(
                disconnectEvent(player)
        );

        assertFalse(
                sessionRegistry.find(PLAYER_ID).isPresent()
        );

        assertFalse(
                presenceRegistry.find(PLAYER_ID).isPresent()
        );

        assertFalse(
                transferRegistry
                        .findByPlayer(PLAYER_ID)
                        .isPresent()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(player)
                        .isEmpty()
        );

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                PlayerSessionAcquireResult
                        .acquired(lease)
                        .status()
        );

        verify(logger).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void removesSessionLeaseWhenPresenceDoesNotExist() {
        registerSessionLease(player);

        listener.onDisconnect(
                disconnectEvent(player)
        );

        assertFalse(
                sessionRegistry.find(PLAYER_ID).isPresent()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(player)
                        .isEmpty()
        );

        verify(logger).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void doesNotLogRemovalWithoutRegisteredState() {
        listener.onDisconnect(
                disconnectEvent(player)
        );

        verify(
                logger,
                never()
        ).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void pendingAcquisitionIsMarkedDisconnectedWithoutRelease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        PlayerDisconnectListener asyncListener =
                listenerWith(coordinator);

        leaseBindingRegistry.begin(
                player,
                ACQUISITION_ID
        );

        asyncListener.onDisconnect(
                disconnectEvent(player)
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(
                any(PlayerSessionLease.class)
        );

        verify(
                logger,
                never()
        ).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void oldDisconnectDoesNotReleaseNewConnectionLease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        PlayerSessionLease newLease =
                lease(2L);

        AuthenticatedPlayerSession session =
                newLease.session();

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement successfulAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement conflictAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                leaseBindingRegistry.beginTracked(
                        newConnection,
                        ACQUISITION_ID,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        newConnection,
                        ACQUISITION_ID,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingResult bindingResult =
                leaseBindingRegistry.bind(
                        newConnection,
                        ACQUISITION_ID,
                        begin.attemptId(),
                        session,
                        newLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                bindingResult
        );

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(oldConnection)
        );

        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection)
                        .orElseThrow()
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(
                any(PlayerSessionLease.class)
        );
    }

    @Test
    void logsAfterAsynchronousLeaseReleaseCompletes() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<Boolean> releaseFuture =
                new CompletableFuture<>();

        PlayerSessionLease lease = lease(1L);

        AuthenticatedPlayerSession session =
                lease.session();

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement successfulAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement conflictAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                leaseBindingRegistry.beginTracked(
                        player,
                        ACQUISITION_ID,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_ID,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingResult bindingResult =
                leaseBindingRegistry.bind(
                        player,
                        ACQUISITION_ID,
                        begin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                bindingResult
        );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(releaseFuture);

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(player)
        );

        verify(coordinator).releaseIfOwned(lease);


        verify(
                logger,
                never()
        ).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );

        releaseFuture.complete(true);


        verify(logger).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void disconnectExternalReleaseCompletionCompletesCanonicalPendingRelease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        Player oldConnection = player(PLAYER_ID);
        Player reconnect = player(PLAYER_ID);

        PlayerSessionLease oldLease = lease(1L);
        AuthenticatedPlayerSession session =
                oldLease.session();

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement successfulAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement conflictAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                leaseBindingRegistry.beginTracked(
                        oldConnection,
                        ACQUISITION_ID,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );
        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_ID,
                        oldBegin.attemptId()
                )
        );
        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                leaseBindingRegistry.bind(
                        oldConnection,
                        ACQUISITION_ID,
                        oldBegin.attemptId(),
                        session,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        CompletableFuture<Boolean> externalReleaseStage =
                new CompletableFuture<>();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(externalReleaseStage);

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(oldConnection)
        );

        verify(coordinator).releaseIfOwned(oldLease);

        UUID reconnectAcquisitionId =
                UUID.fromString(
                        "33333333-4444-5555-6666-777777777777"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult reconnectBegin =
                leaseBindingRegistry.beginTracked(
                        reconnect,
                        reconnectAcquisitionId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                reconnectBegin.decision()
        );

        CompletionStage<Boolean> internalPendingRelease =
                leaseBindingRegistry.awaitPendingRelease(
                        reconnect,
                        reconnectAcquisitionId,
                        PROXY_IDENTITY
                ).orElseThrow();

        assertFalse(
                internalPendingRelease == externalReleaseStage
        );
        assertFalse(
                leaseBindingRegistry.attachReleaseCompletion(
                        oldLease,
                        new CompletableFuture<>()
                ),
                "a different external stage must be inert after "
                        + "the disconnect listener associates the "
                        + "real release stage"
        );

        externalReleaseStage.complete(true);

        assertTrue(
                internalPendingRelease
                        .toCompletableFuture()
                        .isDone()
        );
        assertTrue(
                internalPendingRelease
                        .toCompletableFuture()
                        .join()
        );
        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(oldLease)
        );
    }

    @Test
    void disconnectReleaseFalseCompletesCanonicalPendingReleaseWithExactStage() {
        CompletableFuture<Boolean> externalReleaseStage =
                new CompletableFuture<>();

        TrackedDisconnectRelease release =
                startDisconnectRelease(externalReleaseStage);

        externalReleaseStage.complete(false);

        assertTrue(
                release.internalPendingRelease()
                        .toCompletableFuture()
                        .isDone()
        );
        assertFalse(
                release.internalPendingRelease()
                        .toCompletableFuture()
                        .join()
        );
        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        release.lease()
                )
        );
    }

    @Test
    void disconnectReleaseExceptionCompletesCanonicalPendingReleaseWithExactStage() {
        CompletableFuture<Boolean> externalReleaseStage =
                new CompletableFuture<>();

        TrackedDisconnectRelease release =
                startDisconnectRelease(externalReleaseStage);

        externalReleaseStage.completeExceptionally(
                new IllegalStateException("release failed")
        );

        assertTrue(
                release.internalPendingRelease()
                        .toCompletableFuture()
                        .isCompletedExceptionally()
        );
        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        release.lease()
                )
        );
    }

    @Test
    void disconnectHungReleaseTimesOutWithoutAnyReconnect() {
        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);
        CompletableFuture<Boolean> hungRelease =
                new CompletableFuture<>();
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        ManualPlayerSessionReleaseTimeoutScheduler
                releaseTimeoutScheduler =
                new ManualPlayerSessionReleaseTimeoutScheduler();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(hungRelease);

        listenerWith(
                coordinator,
                releaseTimeoutScheduler
        ).onDisconnect(disconnectEvent(oldConnection));

        verify(coordinator).releaseIfOwned(oldLease);
        assertEquals(1, releaseTimeoutScheduler.scheduledCount());

        releaseTimeoutScheduler.scheduled(0).fire();

        assertFalse(
                leaseBindingRegistry
                        .awaitPendingRelease(
                                oldConnection,
                                ACQUISITION_ID,
                                PROXY_IDENTITY
                        )
                        .isPresent()
        );
        assertFalse(hungRelease.isDone());
        assertFalse(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );
    }

    @Test
    void disconnectHungReleaseDoesNotRequireReconnectForTimeout() {
        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);
        CompletableFuture<Boolean> hungRelease =
                new CompletableFuture<>();
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        ManualPlayerSessionReleaseTimeoutScheduler
                releaseTimeoutScheduler =
                new ManualPlayerSessionReleaseTimeoutScheduler();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(hungRelease);

        listenerWith(
                coordinator,
                releaseTimeoutScheduler
        ).onDisconnect(disconnectEvent(oldConnection));

        releaseTimeoutScheduler.scheduled(0).fire();

        assertFalse(hungRelease.isDone());
        assertFalse(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );
    }

    @Test
    void releaseCompletionBeforeOwnedTimeoutCancelsWatchdog() {
        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);
        CompletableFuture<Boolean> releaseStage =
                new CompletableFuture<>();
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        ManualPlayerSessionReleaseTimeoutScheduler
                releaseTimeoutScheduler =
                new ManualPlayerSessionReleaseTimeoutScheduler();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(releaseStage);

        listenerWith(
                coordinator,
                releaseTimeoutScheduler
        ).onDisconnect(disconnectEvent(oldConnection));

        ManualPlayerSessionReleaseTimeoutScheduler.ScheduledTimeout
                timeout = releaseTimeoutScheduler.scheduled(0);

        releaseStage.complete(true);
        timeout.fire();

        assertTrue(timeout.cancelled());
        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );
    }

    @Test
    void lateExactReleaseCompletionReconcilesOwnedTimeoutQuarantine() {
        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);
        CompletableFuture<Boolean> releaseStage =
                new CompletableFuture<>();
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        ManualPlayerSessionReleaseTimeoutScheduler
                releaseTimeoutScheduler =
                new ManualPlayerSessionReleaseTimeoutScheduler();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(releaseStage);

        listenerWith(
                coordinator,
                releaseTimeoutScheduler
        ).onDisconnect(disconnectEvent(oldConnection));

        releaseTimeoutScheduler.scheduled(0).fire();
        releaseStage.complete(true);

        assertFalse(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );
    }

    @Test
    void differentStageCannotReconcileOwnedTimeoutQuarantine() {
        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);
        CompletableFuture<Boolean> releaseStage =
                new CompletableFuture<>();
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        ManualPlayerSessionReleaseTimeoutScheduler
                releaseTimeoutScheduler =
                new ManualPlayerSessionReleaseTimeoutScheduler();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(releaseStage);

        listenerWith(
                coordinator,
                releaseTimeoutScheduler
        ).onDisconnect(disconnectEvent(oldConnection));

        releaseTimeoutScheduler.scheduled(0).fire();
        leaseBindingRegistry.completeRelease(
                oldLease,
                new CompletableFuture<>(),
                true
        );

        assertFalse(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );
    }

    @Test
    void releaseTimeoutScheduleThrowFailsClosed() {
        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);
        CompletableFuture<Boolean> releaseStage =
                new CompletableFuture<>();
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        ManualPlayerSessionReleaseTimeoutScheduler
                releaseTimeoutScheduler =
                new ManualPlayerSessionReleaseTimeoutScheduler();
        releaseTimeoutScheduler.throwOnSchedule();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(releaseStage);

        listenerWith(
                coordinator,
                releaseTimeoutScheduler
        ).onDisconnect(disconnectEvent(oldConnection));

        assertFalse(releaseStage.isDone());
        assertFalse(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );
    }

    @Test
    void releaseTimeoutScheduleNullFailsClosed() {
        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);
        CompletableFuture<Boolean> releaseStage =
                new CompletableFuture<>();
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        ManualPlayerSessionReleaseTimeoutScheduler
                releaseTimeoutScheduler =
                new ManualPlayerSessionReleaseTimeoutScheduler();
        releaseTimeoutScheduler.nullOnSchedule();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(releaseStage);

        listenerWith(
                coordinator,
                releaseTimeoutScheduler
        ).onDisconnect(disconnectEvent(oldConnection));

        assertFalse(releaseStage.isDone());
        assertFalse(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );
    }

    @Test
    void releaseTimeoutCancelThrowDoesNotBlockCompletion() {
        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);
        CompletableFuture<Boolean> releaseStage =
                new CompletableFuture<>();
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);
        ManualPlayerSessionReleaseTimeoutScheduler
                releaseTimeoutScheduler =
                new ManualPlayerSessionReleaseTimeoutScheduler();
        releaseTimeoutScheduler.throwOnCancel();

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(releaseStage);

        listenerWith(
                coordinator,
                releaseTimeoutScheduler
        ).onDisconnect(disconnectEvent(oldConnection));

        releaseStage.complete(true);

        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );
    }

    @Test
    void completedDisconnectReleaseStageCompletesAfterAttachment() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease = lease(1L);
        AuthenticatedPlayerSession session =
                oldLease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                leaseBindingRegistry.beginTracked(
                        oldConnection,
                        ACQUISITION_ID,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );
        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_ID,
                        oldBegin.attemptId()
                )
        );
        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                leaseBindingRegistry.bind(
                        oldConnection,
                        ACQUISITION_ID,
                        oldBegin.attemptId(),
                        session,
                        oldLease,
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                true,
                                "Player session registered"
                        ),
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                false,
                                "Player session binding conflict"
                        )
                )
        );

        CompletableFuture<Boolean> externalReleaseStage =
                CompletableFuture.completedFuture(true);

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(externalReleaseStage);

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(oldConnection)
        );

        verify(coordinator).releaseIfOwned(oldLease);
        assertFalse(
                leaseBindingRegistry.attachReleaseCompletion(
                        oldLease,
                        new CompletableFuture<>()
                )
        );
        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(oldLease)
        );
    }

    @Test
    void disconnectReleaseThrowBeforeAttachDoesNotRetainRelease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);

        when(coordinator.releaseIfOwned(oldLease))
                .thenThrow(new IllegalStateException("release failed"));

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(oldConnection)
        );

        verify(coordinator).releaseIfOwned(oldLease);
        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(oldLease)
        );
    }

    @Test
    void disconnectReleaseNullBeforeAttachDoesNotRetainRelease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        Player oldConnection = player(PLAYER_ID);
        PlayerSessionLease oldLease =
                bindLeaseForDisconnect(oldConnection);

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(null);

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(oldConnection)
        );

        verify(coordinator).releaseIfOwned(oldLease);
        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(oldLease)
        );
    }

    @Test
    void rejectsNullEvent() {
        assertThrows(
                NullPointerException.class,
                () -> listener.onDisconnect(null)
        );
    }

    @Test
    void rejectsNullConstructorArguments() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        null,
                        presenceRegistry,
                        transferRegistry,
                        releaseService(
                                sessionCoordinator,
                                new ManualPlayerSessionReleaseTimeoutScheduler()
                        ),
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        leaseBindingRegistry,
                        presenceRegistry,
                        transferRegistry,
                        null,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        leaseBindingRegistry,
                        null,
                        transferRegistry,
                        releaseService(
                                sessionCoordinator,
                                new ManualPlayerSessionReleaseTimeoutScheduler()
                        ),
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        leaseBindingRegistry,
                        presenceRegistry,
                        null,
                        releaseService(
                                sessionCoordinator,
                                new ManualPlayerSessionReleaseTimeoutScheduler()
                        ),
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        leaseBindingRegistry,
                        presenceRegistry,
                        transferRegistry,
                        releaseService(
                                sessionCoordinator,
                                new ManualPlayerSessionReleaseTimeoutScheduler()
                        ),
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        leaseBindingRegistry,
                        presenceRegistry,
                        transferRegistry,
                        (BackendCapacityReservationRegistry) null,
                        releaseService(
                                sessionCoordinator,
                                new ManualPlayerSessionReleaseTimeoutScheduler()
                        ),
                        logger
                )
        );
    }

    private PlayerSessionLease registerSessionLease(
            Player exactPlayer
    ) {
        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_000L
                );

        PlayerSessionAcquireResult result =
                sessionCoordinator.acquire(
                        new PlayerSessionLeaseRequest(
                                session,
                                PROXY_IDENTITY
                        )
                ).toCompletableFuture().join();

        PlayerSessionLease lease =
                result.lease().orElseThrow();

        session = lease.session();

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement successfulAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement conflictAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                leaseBindingRegistry.beginTracked(
                        exactPlayer,
                        ACQUISITION_ID,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        exactPlayer,
                        ACQUISITION_ID,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingResult bindingResult =
                leaseBindingRegistry.bind(
                        exactPlayer,
                        ACQUISITION_ID,
                        begin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                bindingResult
        );

        return lease;
    }

    private TrackedDisconnectRelease startDisconnectRelease(
            CompletableFuture<Boolean> externalReleaseStage
    ) {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        Player oldConnection = player(PLAYER_ID);
        Player reconnect = player(PLAYER_ID);

        PlayerSessionLease oldLease = lease(1L);
        AuthenticatedPlayerSession session =
                oldLease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                leaseBindingRegistry.beginTracked(
                        oldConnection,
                        ACQUISITION_ID,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );
        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_ID,
                        oldBegin.attemptId()
                )
        );
        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                leaseBindingRegistry.bind(
                        oldConnection,
                        ACQUISITION_ID,
                        oldBegin.attemptId(),
                        session,
                        oldLease,
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                true,
                                "Player session registered"
                        ),
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                false,
                                "Player session binding conflict"
                        )
                )
        );

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(externalReleaseStage);

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(oldConnection)
        );

        verify(coordinator).releaseIfOwned(oldLease);

        UUID reconnectAcquisitionId =
                UUID.fromString(
                        "33333333-4444-5555-6666-777777777777"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult reconnectBegin =
                leaseBindingRegistry.beginTracked(
                        reconnect,
                        reconnectAcquisitionId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                reconnectBegin.decision()
        );

        CompletionStage<Boolean> internalPendingRelease =
                leaseBindingRegistry.awaitPendingRelease(
                        reconnect,
                        reconnectAcquisitionId,
                        PROXY_IDENTITY
                ).orElseThrow();

        assertFalse(
                internalPendingRelease == externalReleaseStage
        );
        assertFalse(
                leaseBindingRegistry.attachReleaseCompletion(
                        oldLease,
                        new CompletableFuture<>()
                )
        );

        return new TrackedDisconnectRelease(
                oldLease,
                reconnect,
                reconnectAcquisitionId,
                internalPendingRelease
        );
    }

    private PlayerSessionLease bindLeaseForDisconnect(
            Player oldConnection
    ) {
        PlayerSessionLease oldLease = lease(1L);
        AuthenticatedPlayerSession session =
                oldLease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                leaseBindingRegistry.beginTracked(
                        oldConnection,
                        ACQUISITION_ID,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );
        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_ID,
                        oldBegin.attemptId()
                )
        );
        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                leaseBindingRegistry.bind(
                        oldConnection,
                        ACQUISITION_ID,
                        oldBegin.attemptId(),
                        session,
                        oldLease,
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                true,
                                "Player session registered"
                        ),
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                false,
                                "Player session binding conflict"
                        )
                )
        );

        return oldLease;
    }

    private record TrackedDisconnectRelease(
            PlayerSessionLease lease,
            Player reconnect,
            UUID reconnectAcquisitionId,
            CompletionStage<Boolean> internalPendingRelease
    ) {
    }

    private PlayerSessionLease lease(long fencingToken) {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_000L
                ),
                PROXY_IDENTITY,
                fencingToken
        );
    }

    private PlayerDisconnectListener listenerWith(
            PlayerSessionCoordinator coordinator
    ) {
        return new PlayerDisconnectListener(
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                releaseService(
                        coordinator,
                        new ManualPlayerSessionReleaseTimeoutScheduler()
                ),
                logger
        );
    }

    private PlayerDisconnectListener listenerWith(
            PlayerSessionCoordinator coordinator,
            PlayerSessionReleaseTimeoutScheduler releaseTimeoutScheduler
    ) {
        return new PlayerDisconnectListener(
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                releaseService(
                        coordinator,
                        releaseTimeoutScheduler
                ),
                logger
        );
    }

    private PlayerSessionReleaseService releaseService(
            PlayerSessionCoordinator coordinator,
            PlayerSessionReleaseTimeoutScheduler releaseTimeoutScheduler
    ) {
        return new PlayerSessionReleaseService(
                coordinator,
                leaseBindingRegistry,
                releaseTimeoutScheduler,
                logger
        );
    }

    private Player player(UUID playerId) {
        Player mockedPlayer = mock(Player.class);

        when(mockedPlayer.getUniqueId())
                .thenReturn(playerId);

        when(mockedPlayer.getUsername())
                .thenReturn("HarriOcho");

        return mockedPlayer;
    }

    private DisconnectEvent disconnectEvent(
            Player exactPlayer
    ) {
        DisconnectEvent event =
                mock(DisconnectEvent.class);

        when(event.getPlayer())
                .thenReturn(exactPlayer);

        return event;
    }

    private static final class ManualPlayerSessionReleaseTimeoutScheduler
            implements PlayerSessionReleaseTimeoutScheduler {

        private final List<ScheduledTimeout> scheduled =
                new ArrayList<>();
        private boolean throwOnSchedule;
        private boolean nullOnSchedule;
        private boolean throwOnCancel;

        @Override
        public ScheduledReleaseTimeout schedule(
                ReleaseTimeoutKey key,
                Runnable timeout
        ) {
            if (throwOnSchedule) {
                throw new IllegalStateException("schedule failed");
            }

            if (nullOnSchedule) {
                return null;
            }

            ScheduledTimeout scheduledTimeout =
                    new ScheduledTimeout(
                            key,
                            timeout,
                            throwOnCancel
                    );
            scheduled.add(scheduledTimeout);
            return scheduledTimeout;
        }

        void throwOnSchedule() {
            throwOnSchedule = true;
        }

        void nullOnSchedule() {
            nullOnSchedule = true;
        }

        void throwOnCancel() {
            throwOnCancel = true;
        }

        int scheduledCount() {
            return scheduled.size();
        }

        ScheduledTimeout scheduled(int index) {
            return scheduled.get(index);
        }

        private static final class ScheduledTimeout
                implements ScheduledReleaseTimeout {

            private final ReleaseTimeoutKey key;
            private final Runnable timeout;
            private final boolean throwOnCancel;
            private boolean cancelled;

            private ScheduledTimeout(
                    ReleaseTimeoutKey key,
                    Runnable timeout,
                    boolean throwOnCancel
            ) {
                this.key = key;
                this.timeout = timeout;
                this.throwOnCancel = throwOnCancel;
            }

            void fire() {
                if (!cancelled) {
                    timeout.run();
                }
            }

            boolean cancelled() {
                return cancelled;
            }

            @Override
            public void cancel() {
                if (throwOnCancel) {
                    throw new IllegalStateException(
                            "cancel failed"
                    );
                }

                cancelled = true;
            }
        }
    }
}

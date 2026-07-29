package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedPayload;
import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.local.LocalPlayerSessionCoordinator;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerAuthenticationAckSender;
import com.theosfera.proxy.session.PlayerSessionAcquisitionTimeoutScheduler;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry.TerminalAcknowledgement;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerAuthenticatedMessageHandlerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final UUID OTHER_PLAYER_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final long AUTHENTICATED_AT = 1_000L;

    private static final ProxyInstanceIdentity PROXY_IDENTITY =
            new ProxyInstanceIdentity(
                    "proxy-handler-test",
                    UUID.fromString(
                            "0f76e7cc-9c7a-4ec4-8a99-f6d6ed9e1228"
                    )
            );

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerSessionLeaseBindingRegistry leaseBindingRegistry;
    private PlayerSessionCoordinator sessionCoordinator;
    private PlayerAuthenticationAckSender acknowledgementSender;
    private Logger logger;
    private PlayerAuthenticatedMessageHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        leaseBindingRegistry =
                new PlayerSessionLeaseBindingRegistry();

        sessionCoordinator =
                new LocalPlayerSessionCoordinator(
                        sessionRegistry
                );

        acknowledgementSender =
                mock(PlayerAuthenticationAckSender.class);

        logger = mock(Logger.class);

        handler = handlerWith(sessionCoordinator);
    }

    @Test
    void declaresPlayerAuthenticatedMessageType() {
        assertEquals(
                ProtocolMessageType.PLAYER_AUTHENTICATED,
                handler.messageType()
        );
    }

    @Test
    void registersAndBindsAuthenticatedSession() {
        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        handler.handle(fixture.context());

        AuthenticatedPlayerSession session =
                sessionRegistry
                        .find(PLAYER_ID)
                        .orElseThrow();

        PlayerSessionLease lease =
                leaseBindingRegistry
                        .find(fixture.player())
                        .orElseThrow();

        assertEquals(PLAYER_ID, session.playerId());
        assertEquals("HarriOcho", session.playerName());
        assertEquals(
                AUTHENTICATED_AT,
                session.authenticatedAt()
        );

        assertEquals(session, lease.session());
        assertEquals(PROXY_IDENTITY, lease.owner());

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(logger).info(
                "Sesión autenticada registrada para {} "
                        + "({}) desde {}.",
                "HarriOcho",
                PLAYER_ID,
                "auth-1"
        );
    }

    @Test
    void replaysCompletedAcknowledgementWithoutReacquiring() {
        PlayerSessionCoordinator coordinator =
                spy(sessionCoordinator);

        PlayerAuthenticatedMessageHandler replayHandler =
                handlerWith(coordinator);

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        replayHandler.handle(fixture.context());
        replayHandler.handle(fixture.context());

        assertEquals(
                1,
                sessionRegistry.snapshot().size()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        verify(
                acknowledgementSender,
                times(2)
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session already registered"
        );
    }
    @Test
    void rejectsConflictingRequestIdReuseWithoutCancellingOriginalRequest() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession originalSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease originalLease =
                new PlayerSessionLease(
                        originalSession,
                        PROXY_IDENTITY,
                        1L
                );

        CompletableFuture<PlayerSessionAcquireResult>
                originalAcquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(originalAcquisition);

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture original = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        ProtocolEnvelope<?> originalEnvelope =
                original.context().envelope();

        ProtocolEnvelope<PlayerAuthenticatedPayload>
                conflictingEnvelope =
                new ProtocolEnvelope<>(
                        originalEnvelope.version(),
                        originalEnvelope.type(),
                        originalEnvelope.requestId(),
                        originalEnvelope.timestamp() + 1,
                        new PlayerAuthenticatedPayload(
                                PLAYER_ID,
                                "HarriOcho",
                                AUTHENTICATED_AT + 1
                        )
                );

        ProtocolMessageContext conflictingContext =
                new ProtocolMessageContext(
                        original.context().source(),
                        conflictingEnvelope
                );

        asyncHandler.handle(original.context());
        asyncHandler.handle(conflictingContext);

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                conflictingContext,
                PLAYER_ID,
                false,
                "Player authentication request conflict"
        );

        originalAcquisition.complete(
                PlayerSessionAcquireResult.acquired(
                        originalLease
                )
        );

        assertEquals(
                originalLease,
                leaseBindingRegistry
                        .find(original.player())
                        .orElseThrow()
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                original.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(originalLease);
    }
    @Test
    void doesNotStartSecondAcquisitionForPendingExactReplay() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        asyncHandler.handle(fixture.context());
        asyncHandler.handle(fixture.context());

        acquisition.complete(
                PlayerSessionAcquireResult.acquired(lease)
        );

        assertEquals(
                lease,
                leaseBindingRegistry
                        .find(fixture.player())
                        .orElseThrow()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(lease);
    }

    @Test
    void acquisitionTimeoutSendsFailClosedAckAndKeepsTerminalReplay() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                timeoutScheduler =
                new ManualPlayerSessionAcquisitionTimeoutScheduler();

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        timeoutScheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        timeoutHandler.handle(fixture.context());

        verifyNoInteractions(acknowledgementSender);

        PlayerSessionAcquisitionTimeoutScheduler
                .AcquisitionTimeoutKey timeoutKey =
                timeoutScheduler.lastKey();

        assertEquals(
                PLAYER_ID,
                timeoutKey.playerId()
        );

        assertEquals(
                fixture.context().envelope().requestId(),
                timeoutKey.requestId()
        );

        assertTrue(timeoutKey.attemptId() > 0);

        timeoutScheduler.trigger();

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        timeoutHandler.handle(fixture.context());

        verify(
                acknowledgementSender,
                times(2)
        ).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        assertFalse(acquisition.isDone());
    }

    @Test
    void timeoutUsesAtomicTerminalAcknowledgementTransition() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        PlayerSessionLeaseBindingRegistry registry =
                spy(new PlayerSessionLeaseBindingRegistry());

        AtomicReference<PlayerSessionAcquisitionTimeoutScheduler
                .AcquisitionTimeoutKey> keyRef =
                new AtomicReference<>();

        AtomicReference<Runnable> timeoutRef =
                new AtomicReference<>();

        PlayerSessionAcquisitionTimeoutScheduler scheduler =
                (key, timeout) -> {
                    keyRef.set(key);
                    timeoutRef.set(timeout);
                    return () -> {
                    };
                };

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        registry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        scheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        timeoutHandler.handle(fixture.context());

        PlayerSessionAcquisitionTimeoutScheduler
                .AcquisitionTimeoutKey key =
                keyRef.get();

        timeoutRef.get().run();

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement acknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        verify(
                registry,
                times(1)
        ).claimAcquisitionTimeout(
                same(fixture.player()),
                eq(key.requestId()),
                eq(key.attemptId()),
                eq(session),
                eq(acknowledgement)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                registry.beginTracked(
                        fixture.player(),
                        key.requestId(),
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                acknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        assertFalse(acquisition.isDone());
    }

    @Test
    void coordinationFailureUsesAtomicTerminalCompletion() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        PlayerSessionLeaseBindingRegistry registry =
                spy(new PlayerSessionLeaseBindingRegistry());

        PlayerSessionAcquisitionTimeoutScheduler scheduler =
                (key, timeout) -> () -> {
                };

        PlayerAuthenticatedMessageHandler failureHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        registry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        scheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        failureHandler.handle(fixture.context());

        acquisition.completeExceptionally(
                new IllegalStateException(
                        "coordination unavailable"
                )
        );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement acknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        ArgumentCaptor<UUID> requestIdCaptor =
                ArgumentCaptor.forClass(UUID.class);

        ArgumentCaptor<Long> attemptIdCaptor =
                ArgumentCaptor.forClass(Long.class);

        verify(
                registry,
                times(1)
        ).completeTerminalRequest(
                same(fixture.player()),
                requestIdCaptor.capture(),
                attemptIdCaptor.capture(),
                eq(session),
                eq(acknowledgement)
        );

        verify(
                registry,
                never()
        ).claimAcquisitionTimeout(
                any(Player.class),
                any(UUID.class),
                anyLong(),
                any(AuthenticatedPlayerSession.class),
                any(PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement.class)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                registry.beginTracked(
                        fixture.player(),
                        requestIdCaptor.getValue(),
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                acknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void rejectedAcquisitionUsesAtomicTerminalCompletion() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        PlayerSessionLeaseBindingRegistry registry =
                spy(new PlayerSessionLeaseBindingRegistry());

        PlayerSessionAcquisitionTimeoutScheduler scheduler =
                (key, timeout) -> () -> {
                };

        PlayerAuthenticatedMessageHandler rejectedHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        registry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        scheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        rejectedHandler.handle(fixture.context());

        acquisition.complete(
                PlayerSessionAcquireResult.withoutLease(
                        PlayerSessionAcquireResult.Status
                                .COORDINATION_UNAVAILABLE
                )
        );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement acknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        ArgumentCaptor<UUID> requestIdCaptor =
                ArgumentCaptor.forClass(UUID.class);

        ArgumentCaptor<Long> attemptIdCaptor =
                ArgumentCaptor.forClass(Long.class);

        verify(
                registry,
                times(1)
        ).completeTerminalRequest(
                same(fixture.player()),
                requestIdCaptor.capture(),
                attemptIdCaptor.capture(),
                eq(session),
                eq(acknowledgement)
        );

        verify(
                registry,
                never()
        ).claimAcquisitionTimeout(
                any(Player.class),
                any(UUID.class),
                anyLong(),
                any(AuthenticatedPlayerSession.class),
                any(PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement.class)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                registry.beginTracked(
                        fixture.player(),
                        requestIdCaptor.getValue(),
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                acknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void successfulAcquisitionUsesAtomicBindingCompletion() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        PlayerSessionLeaseBindingRegistry registry =
                spy(new PlayerSessionLeaseBindingRegistry());

        PlayerSessionAcquisitionTimeoutScheduler scheduler =
                (key, timeout) -> () -> {
                };

        PlayerAuthenticatedMessageHandler atomicBindingHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        registry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        scheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        atomicBindingHandler.handle(fixture.context());

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        acquisition.complete(
                PlayerSessionAcquireResult.acquired(lease)
        );

        TerminalAcknowledgement successfulAcknowledgement =
                new TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        TerminalAcknowledgement conflictAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        ArgumentCaptor<UUID> requestIdCaptor =
                ArgumentCaptor.forClass(UUID.class);

        ArgumentCaptor<Long> attemptIdCaptor =
                ArgumentCaptor.forClass(Long.class);

        verify(
                registry,
                times(1)
        ).bind(
                same(fixture.player()),
                requestIdCaptor.capture(),
                attemptIdCaptor.capture(),
                eq(session),
                eq(lease),
                eq(successfulAcknowledgement),
                eq(conflictAcknowledgement)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                registry.beginTracked(
                        fixture.player(),
                        requestIdCaptor.getValue(),
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                successfulAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        assertEquals(
                lease,
                registry.find(fixture.player()).orElseThrow()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void lateSuccessfulAcquisitionAfterTimeoutReleasesUnclaimedLease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                timeoutScheduler =
                new ManualPlayerSessionAcquisitionTimeoutScheduler();

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        timeoutScheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        timeoutHandler.handle(fixture.context());

        timeoutScheduler.trigger();

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        acquisition.complete(
                PlayerSessionAcquireResult.acquired(lease)
        );

        verify(
                coordinator,
                times(1)
        ).releaseIfOwned(lease);

        assertTrue(
                leaseBindingRegistry
                        .find(fixture.player())
                        .isEmpty()
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void lateAlreadyOwnedAcquisitionAfterTimeoutReleasesUnclaimedLease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                timeoutScheduler =
                new ManualPlayerSessionAcquisitionTimeoutScheduler();

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        timeoutScheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        timeoutHandler.handle(fixture.context());

        timeoutScheduler.trigger();

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        acquisition.complete(
                PlayerSessionAcquireResult.alreadyOwned(lease)
        );

        verify(
                coordinator,
                times(1)
        ).releaseIfOwned(lease);

        assertTrue(
                leaseBindingRegistry
                        .find(fixture.player())
                        .isEmpty()
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session already registered"
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void schedulingFailureFailsClosedAndReleasesLateSuccessfulLease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        PlayerSessionAcquisitionTimeoutScheduler
                failingScheduler =
                (key, timeout) -> {
                    throw new IllegalStateException(
                            "scheduler unavailable"
                    );
                };

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        failingScheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        timeoutHandler.handle(fixture.context());

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        acquisition.complete(
                PlayerSessionAcquireResult.acquired(lease)
        );

        verify(
                coordinator,
                times(1)
        ).releaseIfOwned(lease);

        assertTrue(
                leaseBindingRegistry
                        .find(fixture.player())
                        .isEmpty()
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void timeoutCancellationFailureDoesNotBlockSuccessfulAcquisition() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        PlayerSessionAcquisitionTimeoutScheduler scheduler =
                (key, timeout) -> () -> {
                    throw new IllegalStateException(
                            "timeout cancellation failed"
                    );
                };

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        scheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        timeoutHandler.handle(fixture.context());

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        acquisition.complete(
                PlayerSessionAcquireResult.acquired(lease)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        assertEquals(
                lease,
                leaseBindingRegistry
                        .find(fixture.player())
                        .orElseThrow()
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(lease);

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void acquisitionResultBeforeTimeoutMakesTimeoutInnocuous() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult>
                acquisition =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(acquisition);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                timeoutScheduler =
                new ManualPlayerSessionAcquisitionTimeoutScheduler();

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        timeoutScheduler,
                        logger
                );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        timeoutHandler.handle(fixture.context());

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        acquisition.complete(
                PlayerSessionAcquireResult.acquired(lease)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        assertEquals(
                lease,
                leaseBindingRegistry
                        .find(fixture.player())
                        .orElseThrow()
        );

        timeoutScheduler.trigger();

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(lease);

        assertEquals(
                lease,
                leaseBindingRegistry
                        .find(fixture.player())
                        .orElseThrow()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void rejectsFurtherDifferentPayloadReuseAfterTerminalConflict() {
        PlayerSessionCoordinator coordinator =
                spy(sessionCoordinator);

        PlayerAuthenticatedMessageHandler replayHandler =
                handlerWith(coordinator);

        ContextFixture original = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        ProtocolEnvelope<?> originalEnvelope =
                original.context().envelope();

        ProtocolMessageContext firstConflict =
                new ProtocolMessageContext(
                        original.context().source(),
                        new ProtocolEnvelope<>(
                                originalEnvelope.version(),
                                originalEnvelope.type(),
                                originalEnvelope.requestId(),
                                originalEnvelope.timestamp() + 1,
                                new PlayerAuthenticatedPayload(
                                        PLAYER_ID,
                                        "HarriOcho",
                                        AUTHENTICATED_AT + 1
                                )
                        )
                );

        ProtocolMessageContext secondConflict =
                new ProtocolMessageContext(
                        original.context().source(),
                        new ProtocolEnvelope<>(
                                originalEnvelope.version(),
                                originalEnvelope.type(),
                                originalEnvelope.requestId(),
                                originalEnvelope.timestamp() + 2,
                                new PlayerAuthenticatedPayload(
                                        PLAYER_ID,
                                        "HarriOcho",
                                        AUTHENTICATED_AT + 2
                                )
                        )
                );

        replayHandler.handle(original.context());
        replayHandler.handle(firstConflict);
        replayHandler.handle(secondConflict);

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                firstConflict,
                PLAYER_ID,
                false,
                "Player authentication request conflict"
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                secondConflict,
                PLAYER_ID,
                false,
                "Player authentication request conflict"
        );
    }
    @Test
    void preservesExistingSessionWhenAcquisitionConflicts() {
        AuthenticatedPlayerSession existingSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        sessionCoordinator.acquire(
                new PlayerSessionLeaseRequest(
                        existingSession,
                        PROXY_IDENTITY
                )
        ).toCompletableFuture().join();

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT + 1
        );

        handler.handle(fixture.context());

        assertEquals(
                existingSession,
                sessionRegistry
                        .find(PLAYER_ID)
                        .orElseThrow()
        );

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session conflict"
        );

        verify(logger).warn(
                "Conflicto de sesión autenticada "
                        + "para {} recibido desde {}.",
                PLAYER_ID,
                "auth-1"
        );
    }

    @Test
    void rejectsAndReleasesLeaseWithDifferentSessionOwnedByThisProxy() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession wrongSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                );

        PlayerSessionLease wrongLease =
                new PlayerSessionLease(
                        wrongSession,
                        PROXY_IDENTITY,
                        1L
                );

        when(coordinator.releaseIfOwned(wrongLease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                wrongLease
                        )
                )
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture fixture =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(fixture.context());

        assertTrue(
                leaseBindingRegistry
                        .find(fixture.player())
                        .isEmpty()
        );

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(coordinator).releaseIfOwned(wrongLease);
    }

    @Test
    void rejectsLeaseOwnedByDifferentProxy() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ProxyInstanceIdentity otherProxy =
                new ProxyInstanceIdentity(
                        "other-proxy",
                        UUID.fromString(
                                "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb"
                        )
                );

        PlayerSessionLease wrongLease =
                new PlayerSessionLease(
                        session,
                        otherProxy,
                        1L
                );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                wrongLease
                        )
                )
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture fixture =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(fixture.context());

        assertTrue(
                leaseBindingRegistry
                        .find(fixture.player())
                        .isEmpty()
        );

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );
    }
    @Test
    void waitsForAcquisitionBeforeAcknowledging() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult> future =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(future);

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        asyncHandler.handle(fixture.context());

        verifyNoInteractions(acknowledgementSender);

        ArgumentCaptor<PlayerSessionLeaseRequest> requestCaptor =
                ArgumentCaptor.forClass(
                        PlayerSessionLeaseRequest.class
                );

        verify(coordinator).acquire(
                requestCaptor.capture()
        );

        PlayerSessionLeaseRequest request =
                requestCaptor.getValue();

        assertEquals(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                ),
                request.session()
        );

        assertEquals(
                PROXY_IDENTITY,
                request.owner()
        );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        request.session(),
                        PROXY_IDENTITY,
                        1L
                );

        future.complete(
                PlayerSessionAcquireResult.acquired(lease)
        );

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        assertEquals(
                lease,
                leaseBindingRegistry
                        .find(fixture.player())
                        .orElseThrow()
        );
    }

    @Test
    void rejectsExceptionalCoordinationFailure() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult> future =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(future);

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        handlerWith(coordinator).handle(
                fixture.context()
        );

        future.completeExceptionally(
                new IllegalStateException(
                        "coordination unavailable"
                )
        );

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        assertTrue(
                leaseBindingRegistry
                        .find(fixture.player())
                        .isEmpty()
        );
    }

    @Test
    void rejectsUnavailableCoordinationResult() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.withoutLease(
                                PlayerSessionAcquireResult.Status
                                        .COORDINATION_UNAVAILABLE
                        )
                )
        );

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        handlerWith(coordinator).handle(
                fixture.context()
        );

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );
    }

    @Test
    void releasesLeaseWithoutLateAckAfterDisconnect() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult> future =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(future);

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        handlerWith(coordinator).handle(
                fixture.context()
        );

        assertTrue(
                leaseBindingRegistry
                        .removeForDisconnect(
                                fixture.player()
                        )
                        .isEmpty()
        );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        new AuthenticatedPlayerSession(
                                PLAYER_ID,
                                "HarriOcho",
                                AUTHENTICATED_AT
                        ),
                        PROXY_IDENTITY,
                        1L
                );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        future.complete(
                PlayerSessionAcquireResult.acquired(lease)
        );

        verify(coordinator).releaseIfOwned(lease);

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(fixture.context()),
                eq(PLAYER_ID),
                anyBoolean(),
                anyString()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(fixture.player())
                        .isEmpty()
        );
    }

    @Test
    void staleOldCallbackReleasesOnlyExactOldLease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult> oldFuture =
                new CompletableFuture<>();

        CompletableFuture<PlayerSessionAcquireResult> newFuture =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                oldFuture,
                newFuture
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(oldConnection.context());
        asyncHandler.handle(newConnection.context());

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        2L
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(
                        CompletableFuture.completedFuture(false)
                );

        newFuture.complete(
                PlayerSessionAcquireResult.acquired(
                        newLease
                )
        );

        oldFuture.complete(
                PlayerSessionAcquireResult.acquired(
                        oldLease
                )
        );


        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .isEmpty()
        );

        verify(acknowledgementSender).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(oldConnection.context()),
                eq(PLAYER_ID),
                anyBoolean(),
                anyString()
        );

        verify(coordinator).releaseIfOwned(oldLease);

        verify(
                coordinator,
                never()
        ).releaseIfOwned(newLease);
    }

    @Test
    void releasesSuccessfulStaleAcquisitionBeforeNewSessionBinds() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<PlayerSessionAcquireResult> oldFuture =
                new CompletableFuture<>();

        CompletableFuture<PlayerSessionAcquireResult> newFuture =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                oldFuture,
                newFuture
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                );

        AuthenticatedPlayerSession oldSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        AuthenticatedPlayerSession newSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        oldSession,
                        PROXY_IDENTITY,
                        1L
                );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        newSession,
                        PROXY_IDENTITY,
                        2L
                );

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        asyncHandler.handle(oldConnection.context());
        asyncHandler.handle(newConnection.context());

        oldFuture.complete(
                PlayerSessionAcquireResult.acquired(
                        oldLease
                )
        );

        verify(coordinator).releaseIfOwned(oldLease);

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(oldConnection.context()),
                eq(PLAYER_ID),
                anyBoolean(),
                anyString()
        );

        newFuture.complete(
                PlayerSessionAcquireResult.acquired(
                        newLease
                )
        );

        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .isEmpty()
        );

        verify(acknowledgementSender).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(newLease);
    }

    @Test
    void failedPendingReleaseCannotExposeRetryToDuplicateEnvelope() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession oldSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        oldSession,
                        PROXY_IDENTITY,
                        1L
                );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                oldLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.withoutLease(
                                PlayerSessionAcquireResult.Status
                                        .CONFLICT
                        )
                )
        );

        PlayerSessionLeaseBindingRegistry coordinatedRegistry =
                spy(
                        new PlayerSessionLeaseBindingRegistry()
                );

        PlayerAuthenticatedMessageHandler[] handlerHolder =
                new PlayerAuthenticatedMessageHandler[1];

        ContextFixture[] connectionHolder =
                new ContextFixture[1];

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();

            handlerHolder[0].handle(
                    connectionHolder[0].context()
            );

            return result;
        }).when(coordinatedRegistry)
                .claimReleaseCompletion(
                        any(Player.class),
                        any(UUID.class),
                        any(CompletionStage.class)
                );

        handlerHolder[0] =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        coordinatedRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        noOpTimeoutScheduler(),
                        logger
                );

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                );

        connectionHolder[0] = newConnection;

        handlerHolder[0].handle(
                oldConnection.context()
        );

        PlayerSessionLease removedLease =
                coordinatedRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        )
                        .orElseThrow();

        assertEquals(oldLease, removedLease);

        assertTrue(
                coordinatedRegistry
                        .reserveReleaseIfUnbound(
                                oldLease
                        )
        );

        handlerHolder[0].handle(
                newConnection.context()
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                anyBoolean(),
                anyString()
        );

        coordinatedRegistry.completeRelease(
                oldLease,
                false
        );

        assertTrue(
                coordinatedRegistry
                        .find(newConnection.player())
                        .isEmpty()
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                eq(true),
                anyString()
        );

        verify(
                coordinator,
                times(2)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(
                any(PlayerSessionLease.class)
        );
    }
    @Test
    void ignoresPendingExactReplayBeforePendingReleaseRetry() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession oldSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        AuthenticatedPlayerSession newSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        oldSession,
                        PROXY_IDENTITY,
                        1L
                );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        newSession,
                        PROXY_IDENTITY,
                        2L
                );

        CompletableFuture<PlayerSessionAcquireResult>
                firstAttempt =
                new CompletableFuture<>();

        CompletableFuture<PlayerSessionAcquireResult>
                retry =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                oldLease
                        )
                ),
                firstAttempt,
                retry
        );

        when(coordinator.releaseIfOwned(newLease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                );

        asyncHandler.handle(oldConnection.context());

        PlayerSessionLease removedLease =
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        )
                        .orElseThrow();

        assertEquals(oldLease, removedLease);

        assertTrue(
                leaseBindingRegistry
                        .reserveReleaseIfUnbound(
                                oldLease
                        )
        );

        asyncHandler.handle(newConnection.context());
        asyncHandler.handle(newConnection.context());

        firstAttempt.complete(
                PlayerSessionAcquireResult.withoutLease(
                        PlayerSessionAcquireResult.Status.CONFLICT
                )
        );

        leaseBindingRegistry.completeRelease(
                oldLease,
                true
        );

        retry.complete(
                PlayerSessionAcquireResult.acquired(
                        newLease
                )
        );

        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                eq(false),
                anyString()
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(newLease);

        verify(
                coordinator,
                times(3)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }
    @Test
    void claimsPendingReleaseCompletionOnlyOnceForDuplicateEnvelope() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        2L
                );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                oldLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.alreadyOwned(
                                oldLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                newLease
                        )
                )
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(oldConnection.context());

        PlayerSessionLease removedLease =
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        )
                        .orElseThrow();

        assertEquals(oldLease, removedLease);

        assertTrue(
                leaseBindingRegistry
                        .reserveReleaseIfUnbound(
                                oldLease
                        )
        );

        asyncHandler.handle(newConnection.context());
        asyncHandler.handle(newConnection.context());

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                anyBoolean(),
                anyString()
        );

        leaseBindingRegistry.completeRelease(
                oldLease,
                true
        );

        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session already registered"
        );
        verify(
                coordinator,
                times(3)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

    }
    @Test
    void waitsForPendingReleaseBeforeRejectingNewSessionConflict() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession oldSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        AuthenticatedPlayerSession newSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        oldSession,
                        PROXY_IDENTITY,
                        1L
                );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        newSession,
                        PROXY_IDENTITY,
                        2L
                );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                oldLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.withoutLease(
                                PlayerSessionAcquireResult.Status
                                        .CONFLICT
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                newLease
                        )
                )
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                );

        asyncHandler.handle(oldConnection.context());

        PlayerSessionLease removedLease =
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        )
                        .orElseThrow();

        assertEquals(oldLease, removedLease);

        assertTrue(
                leaseBindingRegistry
                        .reserveReleaseIfUnbound(
                                oldLease
                        )
        );

        asyncHandler.handle(newConnection.context());

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                anyBoolean(),
                anyString()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(newConnection.player())
                        .isEmpty()
        );

        leaseBindingRegistry.completeRelease(
                oldLease,
                true
        );

        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        verify(acknowledgementSender).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                coordinator,
                times(3)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }
    @Test
    void waitsForPendingReleaseBeforeAcknowledgingRapidReconnect() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        2L
                );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                oldLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.alreadyOwned(
                                oldLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                newLease
                        )
                )
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(oldConnection.context());

        PlayerSessionLease removedLease =
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        )
                        .orElseThrow();

        assertEquals(oldLease, removedLease);

        assertTrue(
                leaseBindingRegistry
                        .reserveReleaseIfUnbound(
                                oldLease
                        )
        );


        asyncHandler.handle(newConnection.context());

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                anyBoolean(),
                anyString()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(newConnection.player())
                        .isEmpty()
        );

        leaseBindingRegistry.completeRelease(
                oldLease,
                true
        );


        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        verify(acknowledgementSender).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                coordinator,
                times(3)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
    }

    @Test
    void pendingReleaseTimeoutFailsClosedAndIgnoresLateReleaseCompletion() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                timeoutScheduler =
                new ManualPlayerSessionAcquisitionTimeoutScheduler();

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        timeoutScheduler,
                        logger
                );

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        UUID oldAcquisitionId =
                UUID.fromString(
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        TerminalAcknowledgement successfulAcknowledgement =
                new TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        TerminalAcknowledgement conflictAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                leaseBindingRegistry.beginTracked(
                        oldConnection.player(),
                        oldAcquisitionId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );

        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        oldConnection.player(),
                        oldAcquisitionId,
                        oldBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                leaseBindingRegistry.bind(
                        oldConnection.player(),
                        oldAcquisitionId,
                        oldBegin.attemptId(),
                        session,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                oldLease,
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        ).orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult
                                .alreadyOwned(oldLease)
                )
        );

        UUID requestId =
                newConnection
                        .context()
                        .envelope()
                        .requestId();

        int schedulesBefore =
                timeoutScheduler.scheduledCount();

        timeoutHandler.handle(
                newConnection.context()
        );

        assertEquals(
                schedulesBefore + 2,
                timeoutScheduler.scheduledCount()
        );

        ManualPlayerSessionAcquisitionTimeoutScheduler
                .ScheduledTimeout acquireTimeout =
                timeoutScheduler.scheduled(schedulesBefore);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                .ScheduledTimeout pendingReleaseTimeout =
                timeoutScheduler.scheduled(schedulesBefore + 1);

        assertTrue(acquireTimeout.cancelled());
        assertFalse(pendingReleaseTimeout.cancelled());
        assertEquals(
                acquireTimeout.key().playerId(),
                pendingReleaseTimeout.key().playerId()
        );
        assertEquals(
                acquireTimeout.key().requestId(),
                pendingReleaseTimeout.key().requestId()
        );
        assertEquals(
                acquireTimeout.key().attemptId(),
                pendingReleaseTimeout.key().attemptId()
        );
        assertEquals(
                PLAYER_ID,
                pendingReleaseTimeout.key().playerId()
        );
        assertEquals(
                requestId,
                pendingReleaseTimeout.key().requestId()
        );

        verifyNoInteractions(acknowledgementSender);
        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        pendingReleaseTimeout.trigger();

        TerminalAcknowledgement failClosedAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                leaseBindingRegistry.beginTracked(
                        newConnection.player(),
                        requestId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                failClosedAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        timeoutHandler.handle(
                newConnection.context()
        );

        verify(
                acknowledgementSender,
                times(2)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        int schedulesBeforeLateRelease =
                timeoutScheduler.scheduledCount();

        leaseBindingRegistry.completeRelease(
                oldLease,
                true
        );

        assertEquals(
                schedulesBeforeLateRelease,
                timeoutScheduler.scheduledCount()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        verify(
                acknowledgementSender,
                times(2)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replayAfterLateRelease =
                leaseBindingRegistry.beginTracked(
                        newConnection.player(),
                        requestId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replayAfterLateRelease.decision()
        );

        assertEquals(
                failClosedAcknowledgement,
                replayAfterLateRelease
                        .acknowledgement()
                .orElseThrow()
        );
    }

    @Test
    void pendingReleaseWatchdogCancellationFailureDoesNotBlockRetry() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                timeoutScheduler =
                new ManualPlayerSessionAcquisitionTimeoutScheduler();

        timeoutScheduler.throwOnCancelIndex(1);

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        timeoutScheduler,
                        logger
                );

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        UUID oldAcquisitionId =
                UUID.fromString(
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        2L
                );

        TerminalAcknowledgement successfulAcknowledgement =
                new TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        TerminalAcknowledgement conflictAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                leaseBindingRegistry.beginTracked(
                        oldConnection.player(),
                        oldAcquisitionId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );

        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        oldConnection.player(),
                        oldAcquisitionId,
                        oldBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                leaseBindingRegistry.bind(
                        oldConnection.player(),
                        oldAcquisitionId,
                        oldBegin.attemptId(),
                        session,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                oldLease,
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        ).orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult
                                .alreadyOwned(oldLease)
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult
                                .acquired(newLease)
                )
        );

        UUID requestId =
                newConnection
                        .context()
                        .envelope()
                        .requestId();

        timeoutHandler.handle(
                newConnection.context()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
        assertEquals(
                2,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                2,
                timeoutScheduler.scheduledCount()
        );

        ManualPlayerSessionAcquisitionTimeoutScheduler
                .ScheduledTimeout acquireTimeout =
                timeoutScheduler.scheduled(0);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                .ScheduledTimeout pendingReleaseTimeout =
                timeoutScheduler.scheduled(1);

        assertTrue(acquireTimeout.cancelled());
        assertFalse(pendingReleaseTimeout.cancelled());
        assertEquals(
                0,
                pendingReleaseTimeout.cancelAttempts()
        );
        assertFalse(acquireTimeout == pendingReleaseTimeout);
        assertEquals(
                PLAYER_ID,
                acquireTimeout.key().playerId()
        );
        assertEquals(
                requestId,
                acquireTimeout.key().requestId()
        );
        assertEquals(
                PLAYER_ID,
                pendingReleaseTimeout.key().playerId()
        );
        assertEquals(
                requestId,
                pendingReleaseTimeout.key().requestId()
        );
        assertEquals(
                acquireTimeout.key().attemptId(),
                pendingReleaseTimeout.key().attemptId()
        );

        verifyNoInteractions(acknowledgementSender);

        leaseBindingRegistry.completeRelease(
                oldLease,
                true
        );

        verify(
                coordinator,
                times(2)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
        assertEquals(
                3,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                3,
                timeoutScheduler.scheduledCount()
        );

        ManualPlayerSessionAcquisitionTimeoutScheduler
                .ScheduledTimeout retryTimeout =
                timeoutScheduler.scheduled(2);

        assertEquals(
                PLAYER_ID,
                retryTimeout.key().playerId()
        );
        assertEquals(
                requestId,
                retryTimeout.key().requestId()
        );
        assertFalse(
                acquireTimeout.key().attemptId()
                        == retryTimeout.key().attemptId()
        );
        assertEquals(
                acquireTimeout.key().attemptId(),
                pendingReleaseTimeout.key().attemptId()
        );
        assertEquals(
                1,
                pendingReleaseTimeout.cancelAttempts()
        );
        assertTrue(retryTimeout.cancelled());

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );
        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                eq(false),
                anyString()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                leaseBindingRegistry.beginTracked(
                        newConnection.player(),
                        requestId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );
        assertEquals(
                successfulAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );
        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );
        assertTrue(
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .isEmpty()
        );

        assertFalse(pendingReleaseTimeout.cancelled());
        pendingReleaseTimeout.trigger();

        assertEquals(
                3,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                3,
                timeoutScheduler.scheduledCount()
        );
        verify(
                coordinator,
                times(2)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
        verify(
                acknowledgementSender,
                times(1)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );
        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                eq(false),
                anyString()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replayAfterWatchdog =
                leaseBindingRegistry.beginTracked(
                        newConnection.player(),
                        requestId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replayAfterWatchdog.decision()
        );
        assertEquals(
                successfulAcknowledgement,
                replayAfterWatchdog.acknowledgement().orElseThrow()
        );
        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        assertTrue(acquireTimeout.cancelled());
        assertEquals(
                1,
                acquireTimeout.cancelAttempts()
        );
        assertFalse(acquireTimeout.triggered());
        assertFalse(pendingReleaseTimeout.cancelled());
        assertEquals(
                1,
                pendingReleaseTimeout.cancelAttempts()
        );
        assertTrue(pendingReleaseTimeout.triggered());
        assertTrue(retryTimeout.cancelled());
        assertEquals(
                1,
                retryTimeout.cancelAttempts()
        );
        assertFalse(retryTimeout.triggered());
    }

    @Test
    void pendingReleaseWatchdogSchedulingFailureFailsClosedImmediately() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                timeoutScheduler =
                new ManualPlayerSessionAcquisitionTimeoutScheduler();

        timeoutScheduler.throwOnScheduleIndex(1);

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        timeoutScheduler,
                        logger
                );

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        UUID oldAcquisitionId =
                UUID.fromString(
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        TerminalAcknowledgement successfulAcknowledgement =
                new TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        TerminalAcknowledgement conflictAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                leaseBindingRegistry.beginTracked(
                        oldConnection.player(),
                        oldAcquisitionId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );

        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        oldConnection.player(),
                        oldAcquisitionId,
                        oldBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                leaseBindingRegistry.bind(
                        oldConnection.player(),
                        oldAcquisitionId,
                        oldBegin.attemptId(),
                        session,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                oldLease,
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        ).orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult
                                .alreadyOwned(oldLease)
                )
        );

        UUID requestId =
                newConnection
                        .context()
                        .envelope()
                        .requestId();

        assertEquals(
                0,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                0,
                timeoutScheduler.scheduledCount()
        );

        timeoutHandler.handle(
                newConnection.context()
        );

        assertEquals(
                2,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                1,
                timeoutScheduler.scheduledCount()
        );
        assertTrue(
                timeoutScheduler.scheduled(0).cancelled()
        );

        TerminalAcknowledgement failClosedAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        assertEquals(
                2,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                1,
                timeoutScheduler.scheduledCount()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                leaseBindingRegistry.beginTracked(
                        newConnection.player(),
                        requestId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                failClosedAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        timeoutHandler.handle(
                newConnection.context()
        );

        verify(
                acknowledgementSender,
                times(2)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
        assertEquals(
                2,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                1,
                timeoutScheduler.scheduledCount()
        );

        int scheduleAttemptsBeforeLateRelease =
                timeoutScheduler.scheduleAttempts();
        int scheduledCountBeforeLateRelease =
                timeoutScheduler.scheduledCount();

        leaseBindingRegistry.completeRelease(
                oldLease,
                true
        );

        assertEquals(
                scheduleAttemptsBeforeLateRelease,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                scheduledCountBeforeLateRelease,
                timeoutScheduler.scheduledCount()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
        verify(
                acknowledgementSender,
                times(2)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replayAfterLateRelease =
                leaseBindingRegistry.beginTracked(
                        newConnection.player(),
                        requestId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replayAfterLateRelease.decision()
        );

        assertEquals(
                failClosedAcknowledgement,
                replayAfterLateRelease
                        .acknowledgement()
                        .orElseThrow()
        );
    }

    @Test
    void pendingReleaseWatchdogNullScheduleFailsClosedImmediately() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        ManualPlayerSessionAcquisitionTimeoutScheduler
                timeoutScheduler =
                new ManualPlayerSessionAcquisitionTimeoutScheduler();

        timeoutScheduler.nullOnScheduleIndex(1);

        PlayerAuthenticatedMessageHandler timeoutHandler =
                new PlayerAuthenticatedMessageHandler(
                        coordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        timeoutScheduler,
                        logger
                );

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        UUID oldAcquisitionId =
                UUID.fromString(
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                );

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        TerminalAcknowledgement successfulAcknowledgement =
                new TerminalAcknowledgement(
                        true,
                        "Player session registered"
                );

        TerminalAcknowledgement conflictAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                leaseBindingRegistry.beginTracked(
                        oldConnection.player(),
                        oldAcquisitionId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );

        assertTrue(
                leaseBindingRegistry.claimAcquisitionResult(
                        oldConnection.player(),
                        oldAcquisitionId,
                        oldBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                leaseBindingRegistry.bind(
                        oldConnection.player(),
                        oldAcquisitionId,
                        oldBegin.attemptId(),
                        session,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                oldLease,
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        ).orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry.reserveReleaseIfUnbound(
                        oldLease
                )
        );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult
                                .alreadyOwned(oldLease)
                )
        );

        UUID requestId =
                newConnection
                        .context()
                        .envelope()
                        .requestId();

        assertEquals(
                0,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                0,
                timeoutScheduler.scheduledCount()
        );

        timeoutHandler.handle(
                newConnection.context()
        );

        assertEquals(
                2,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                1,
                timeoutScheduler.scheduledCount()
        );
        assertTrue(
                timeoutScheduler.scheduled(0).cancelled()
        );

        TerminalAcknowledgement failClosedAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        verify(
                acknowledgementSender,
                times(1)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );

        assertEquals(
                2,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                1,
                timeoutScheduler.scheduledCount()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                leaseBindingRegistry.beginTracked(
                        newConnection.player(),
                        requestId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                failClosedAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        timeoutHandler.handle(
                newConnection.context()
        );

        verify(
                acknowledgementSender,
                times(2)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
        assertEquals(
                2,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                1,
                timeoutScheduler.scheduledCount()
        );

        int scheduleAttemptsBeforeLateRelease =
                timeoutScheduler.scheduleAttempts();
        int scheduledCountBeforeLateRelease =
                timeoutScheduler.scheduledCount();

        leaseBindingRegistry.completeRelease(
                oldLease,
                true
        );

        assertEquals(
                scheduleAttemptsBeforeLateRelease,
                timeoutScheduler.scheduleAttempts()
        );
        assertEquals(
                scheduledCountBeforeLateRelease,
                timeoutScheduler.scheduledCount()
        );

        verify(
                coordinator,
                times(1)
        ).acquire(
                any(PlayerSessionLeaseRequest.class)
        );
        verify(
                acknowledgementSender,
                times(2)
        ).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replayAfterLateRelease =
                leaseBindingRegistry.beginTracked(
                        newConnection.player(),
                        requestId,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replayAfterLateRelease.decision()
        );

        assertEquals(
                failClosedAcknowledgement,
                replayAfterLateRelease
                        .acknowledgement()
                        .orElseThrow()
        );
    }
    @Test
    void lateAuthenticationFromOldConnectionCannotReclaimLease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease sharedLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                sharedLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.alreadyOwned(
                                sharedLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.alreadyOwned(
                                sharedLease
                        )
                )
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(oldConnection.context());
        asyncHandler.handle(newConnection.context());

        assertEquals(
                sharedLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        asyncHandler.handle(oldConnection.context());

        assertEquals(
                sharedLease,
                leaseBindingRegistry
                        .find(newConnection.player())
                        .orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .isEmpty()
        );

        verify(
                acknowledgementSender,
                times(2)
        ).send(
                oldConnection.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                oldConnection.context(),
                PLAYER_ID,
                true,
                "Player session already registered"
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(sharedLease);
    }
    @Test
    void disconnectedNewConnectionDoesNotReleaseOldActiveLease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        CompletableFuture<PlayerSessionAcquireResult> newFuture =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                oldLease
                        )
                ),
                newFuture
        );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(oldConnection.context());
        asyncHandler.handle(newConnection.context());

        assertEquals(
                oldLease,
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry
                        .removeForDisconnect(
                                newConnection.player()
                        )
                        .isEmpty()
        );

        newFuture.complete(
                PlayerSessionAcquireResult.alreadyOwned(
                        oldLease
                )
        );

        assertEquals(
                oldLease,
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(newConnection.player())
                        .isEmpty()
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(oldLease);

        verify(
                acknowledgementSender,
                never()
        ).send(
                eq(newConnection.context()),
                eq(PLAYER_ID),
                anyBoolean(),
                anyString()
        );
    }
    @Test
    void releasesDeferredOldLeaseWhenNewAcquisitionConflicts() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        CompletableFuture<PlayerSessionAcquireResult> newFuture =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                oldLease
                        )
                ),
                newFuture
        );

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(oldConnection.context());
        asyncHandler.handle(newConnection.context());

        assertEquals(
                oldLease,
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .orElseThrow()
        );

        assertTrue(
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        )
                        .isEmpty()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .isEmpty()
        );

        newFuture.complete(
                PlayerSessionAcquireResult.withoutLease(
                        PlayerSessionAcquireResult.Status.CONFLICT
                )
        );

        verify(coordinator).releaseIfOwned(oldLease);

        verify(acknowledgementSender).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session conflict"
        );

        assertTrue(
                leaseBindingRegistry
                        .find(newConnection.player())
                        .isEmpty()
        );
    }

    @Test
    void releasesDeferredOldLeaseWhenNewCoordinationFails() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        PlayerSessionLease oldLease =
                new PlayerSessionLease(
                        session,
                        PROXY_IDENTITY,
                        1L
                );

        CompletableFuture<PlayerSessionAcquireResult> newFuture =
                new CompletableFuture<>();

        when(coordinator.acquire(
                any(PlayerSessionLeaseRequest.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                oldLease
                        )
                ),
                newFuture
        );

        when(coordinator.releaseIfOwned(oldLease))
                .thenReturn(
                        CompletableFuture.completedFuture(true)
                );

        PlayerAuthenticatedMessageHandler asyncHandler =
                handlerWith(coordinator);

        ContextFixture oldConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        ContextFixture newConnection =
                authenticatedContext(
                        PLAYER_ID,
                        "HarriOcho",
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        asyncHandler.handle(oldConnection.context());
        asyncHandler.handle(newConnection.context());

        assertTrue(
                leaseBindingRegistry
                        .removeForDisconnect(
                                oldConnection.player()
                        )
                        .isEmpty()
        );

        newFuture.completeExceptionally(
                new IllegalStateException(
                        "coordination unavailable"
                )
        );

        verify(coordinator).releaseIfOwned(oldLease);

        verify(acknowledgementSender).send(
                newConnection.context(),
                PLAYER_ID,
                false,
                "Player session coordination unavailable"
        );

        assertTrue(
                leaseBindingRegistry
                        .find(oldConnection.player())
                        .isEmpty()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(newConnection.player())
                        .isEmpty()
        );
    }
    @Test
    void rejectsPayloadForDifferentPlayerId() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        ContextFixture fixture = authenticatedContext(
                OTHER_PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        handlerWith(coordinator).handle(
                fixture.context()
        );

        verifyNoInteractions(coordinator);

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player identity mismatch"
        );
    }

    @Test
    void rejectsPayloadForDifferentPlayerName() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "OtroNombre",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        handlerWith(coordinator).handle(
                fixture.context()
        );

        verifyNoInteractions(coordinator);

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                false,
                "Player identity mismatch"
        );
    }

    @Test
    void rejectsEnvelopeWithUnexpectedPayload() {
        ProtocolEnvelope<PingPayload> envelope =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PLAYER_AUTHENTICATED,
                        new PingPayload(1_000L)
                );

        ContextFixture fixture = createContext(
                "auth-1",
                PLAYER_ID,
                "HarriOcho",
                envelope
        );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> handler.handle(
                                fixture.context()
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "PlayerAuthenticatedPayload"
                )
        );

        verifyNoInteractions(acknowledgementSender);
    }

    @Test
    void rejectsNullContext() {
        assertThrows(
                NullPointerException.class,
                () -> handler.handle(null)
        );
    }

    @Test
    void rejectsNullConstructorArguments() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        null,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        noOpTimeoutScheduler(),
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        sessionCoordinator,
                        null,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        noOpTimeoutScheduler(),
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        null,
                        acknowledgementSender,
                        noOpTimeoutScheduler(),
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        null,
                        noOpTimeoutScheduler(),
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        null,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        PROXY_IDENTITY,
                        acknowledgementSender,
                        noOpTimeoutScheduler(),
                        null
                )
        );
    }

    private PlayerAuthenticatedMessageHandler handlerWith(
            PlayerSessionCoordinator coordinator
    ) {
        return new PlayerAuthenticatedMessageHandler(
                coordinator,
                leaseBindingRegistry,
                PROXY_IDENTITY,
                acknowledgementSender,
                noOpTimeoutScheduler(),
                logger
        );
    }

    private PlayerSessionAcquisitionTimeoutScheduler
    noOpTimeoutScheduler() {
        return (key, timeout) -> () -> {
        };
    }

    private ContextFixture authenticatedContext(
            UUID carrierId,
            String carrierName,
            UUID payloadId,
            String payloadName,
            long authenticatedAt
    ) {
        return createContext(
                "auth-1",
                carrierId,
                carrierName,
                ProtocolEnvelope.create(
                        ProtocolMessageType
                                .PLAYER_AUTHENTICATED,
                        new PlayerAuthenticatedPayload(
                                payloadId,
                                payloadName,
                                authenticatedAt
                        )
                )
        );
    }

    private ContextFixture createContext(
            String serverName,
            UUID carrierId,
            String carrierName,
            ProtocolEnvelope<?> envelope
    ) {
        ServerConnection source =
                mock(ServerConnection.class);

        ServerInfo serverInfo =
                mock(ServerInfo.class);

        Player carrier = mock(Player.class);

        when(source.getServerInfo())
                .thenReturn(serverInfo);

        when(serverInfo.getName())
                .thenReturn(serverName);

        when(source.getPlayer())
                .thenReturn(carrier);

        when(carrier.getUniqueId())
                .thenReturn(carrierId);

        when(carrier.getUsername())
                .thenReturn(carrierName);

        return new ContextFixture(
                new ProtocolMessageContext(
                        source,
                        envelope
                ),
                carrier
        );
    }

    private static final class
    ManualPlayerSessionAcquisitionTimeoutScheduler
            implements PlayerSessionAcquisitionTimeoutScheduler {

        private final List<ScheduledTimeout> scheduled =
                new ArrayList<>();
        private int scheduleAttempts;
        private Integer throwOnScheduleIndex;
        private Integer nullOnScheduleIndex;
        private Integer throwOnCancelIndex;

        @Override
        public ScheduledAcquisitionTimeout schedule(
                AcquisitionTimeoutKey key,
                Runnable timeout
        ) {
            int scheduleIndex = scheduleAttempts;
            scheduleAttempts++;

            if (throwOnScheduleIndex != null
                    && throwOnScheduleIndex == scheduleIndex) {
                throw new IllegalStateException(
                        "synthetic pending release scheduling failure"
                );
            }

            if (nullOnScheduleIndex != null
                    && nullOnScheduleIndex == scheduleIndex) {
                return null;
            }

            ScheduledTimeout scheduledTimeout =
                    new ScheduledTimeout(
                            scheduleIndex,
                            key,
                            timeout
                    );

            scheduled.add(scheduledTimeout);

            return scheduledTimeout::cancel;
        }

        private void throwOnScheduleIndex(int index) {
            if (index < 0) {
                throw new IllegalArgumentException(
                        "index cannot be negative"
                );
            }

            throwOnScheduleIndex = index;
        }

        private void nullOnScheduleIndex(int index) {
            if (index < 0) {
                throw new IllegalArgumentException(
                        "index cannot be negative"
                );
            }

            nullOnScheduleIndex = index;
        }

        private void throwOnCancelIndex(int index) {
            if (index < 0) {
                throw new IllegalArgumentException(
                        "index cannot be negative"
                );
            }

            throwOnCancelIndex = index;
        }

        private int scheduleAttempts() {
            return scheduleAttempts;
        }

        private AcquisitionTimeoutKey lastKey() {
            return scheduled
                    .get(scheduled.size() - 1)
                    .key();
        }

        private int scheduledCount() {
            return scheduled.size();
        }

        private ScheduledTimeout scheduled(int index) {
            return scheduled.get(index);
        }

        private void trigger() {
            if (scheduled.isEmpty()) {
                return;
            }

            scheduled
                    .get(scheduled.size() - 1)
                    .trigger();
        }

        private final class ScheduledTimeout {

            private final int scheduleIndex;
            private final AcquisitionTimeoutKey key;
            private final Runnable timeout;
            private boolean cancelled;
            private boolean triggered;
            private int cancelAttempts;

            private ScheduledTimeout(
                    int scheduleIndex,
                    AcquisitionTimeoutKey key,
                    Runnable timeout
            ) {
                this.scheduleIndex = scheduleIndex;
                this.key = key;
                this.timeout = timeout;
            }

            private AcquisitionTimeoutKey key() {
                return key;
            }

            private boolean cancelled() {
                return cancelled;
            }

            private boolean triggered() {
                return triggered;
            }

            private int cancelAttempts() {
                return cancelAttempts;
            }

            private void cancel() {
                cancelAttempts++;

                if (throwOnCancelIndex != null
                        && throwOnCancelIndex == scheduleIndex) {
                    throw new IllegalStateException(
                            "synthetic pending release timeout "
                                    + "cancellation failure"
                    );
                }

                cancelled = true;
            }

            private void trigger() {
                if (cancelled || triggered || timeout == null) {
                    return;
                }

                triggered = true;
                timeout.run();
            }
        }
    }

    private record ContextFixture(
            ProtocolMessageContext context,
            Player player
    ) {
    }
}

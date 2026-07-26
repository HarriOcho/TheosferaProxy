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
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void treatsRepeatedSessionAsAlreadyRegistered() {
        ContextFixture fixture = authenticatedContext(
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "HarriOcho",
                AUTHENTICATED_AT
        );

        handler.handle(fixture.context());
        handler.handle(fixture.context());

        assertEquals(
                1,
                sessionRegistry.snapshot().size()
        );

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(acknowledgementSender).send(
                fixture.context(),
                PLAYER_ID,
                true,
                "Player session already registered"
        );

        verify(logger).debug(
                "Sesión autenticada ya registrada para {} "
                        + "({}).",
                "HarriOcho",
                PLAYER_ID
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
                        PlayerSessionAcquireResult.alreadyOwned(
                                oldLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.acquired(
                                newLease
                        )
                ),
                CompletableFuture.completedFuture(
                        PlayerSessionAcquireResult.alreadyOwned(
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
                times(4)
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
                times(1)
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
                logger
        );
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

    private record ContextFixture(
            ProtocolMessageContext context,
            Player player
    ) {
    }
}

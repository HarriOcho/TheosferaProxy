package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.TransferRequestPayload;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapReservation;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.BackendCapacityReservation;
import com.theosfera.proxy.transfer.BackendCapacityReservationResult;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.TransferResultSender;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferRequestMessageHandlerTest {

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private static final UUID OTHER_PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final long NOW =
            1_750_000_000_000L;

    private ProxyServer proxyServer;
    private BackendIdentityRegistry identityRegistry;
    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerServerPresenceRegistry presenceRegistry;
    private PendingPlayerTransferRegistry transferRegistry;
    private BackendBootstrapRegistry bootstrapRegistry;
    private TransferTargetResolver targetResolver;
    private PlayerTransferExecutor transferExecutor;
    private TransferResultSender resultSender;
    private Logger logger;
    private Player player;
    private ServerConnection source;
    private RegisteredServer target;
    private TransferRequestMessageHandler handler;

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);

        identityRegistry =
                new BackendIdentityRegistry();

        identityRegistry.register(
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                )
        );

        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );

        transferRegistry =
                new PendingPlayerTransferRegistry();

        bootstrapRegistry =
                new BackendBootstrapRegistry();

        targetResolver =
                mock(TransferTargetResolver.class);

        when(targetResolver.reserveCapacity(
                any(),
                any()
        )).thenReturn(
                BackendCapacityReservationResult.RESERVED
        );

        transferExecutor =
                mock(PlayerTransferExecutor.class);

        resultSender =
                mock(TransferResultSender.class);

        logger = mock(Logger.class);
        player = mock(Player.class);
        source = mock(ServerConnection.class);
        target = mock(RegisteredServer.class);

        configureSourceConnection();
        configureTarget();

        Clock clock = Clock.fixed(
                Instant.ofEpochMilli(NOW),
                ZoneOffset.UTC
        );

        handler = new TransferRequestMessageHandler(
                proxyServer,
                identityRegistry,
                sessionRegistry,
                presenceRegistry,
                transferRegistry,
                bootstrapRegistry,
                targetResolver,
                transferExecutor,
                resultSender,
                logger,
                clock
        );
    }

    @Test
    void declaresTransferRequestMessageType() {
        assertEquals(
                ProtocolMessageType.TRANSFER_REQUEST,
                handler.messageType()
        );
    }

    @Test
    void transfersAuthenticatedPlayerFromMatchingSource() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(transferExecutor).execute(player, target);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );

        assertFalse(
                transferRegistry
                        .findByPlayer(PLAYER_ID)
                        .isPresent()
        );

        assertFalse(
                presenceRegistry
                        .find(PLAYER_ID)
                        .isPresent()
        );
    }

    @Test
    void reservesBootstrapForColdTargetAndKeepsItOnSuccess() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        BackendBootstrapReservation reservation =
                bootstrapRegistry
                        .findByTarget("skyblock-1")
                        .orElseThrow();

        assertEquals(
                REQUEST_ID,
                reservation.requestId()
        );

        assertEquals(
                PLAYER_ID,
                reservation.playerId()
        );

        verify(transferExecutor).execute(
                player,
                target
        );

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    @Test
    void releasesBootstrapWhenTransferFails() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.failed()
                        )
                );

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        assertTrue(
                bootstrapRegistry
                        .findByTarget("skyblock-1")
                        .isEmpty()
        );

        assertTrue(
                bootstrapRegistry
                        .findByRequest(REQUEST_ID)
                        .isEmpty()
        );
    }

    @Test
    void rejectsBootstrapWhenTargetIsAlreadyReserved() {
        registerPlayerState();

        bootstrapRegistry.register(
                new BackendBootstrapReservation(
                        "skyblock-1",
                        UUID.randomUUID(),
                        OTHER_PLAYER_ID,
                        NOW - 1_000L
                )
        );

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Target backend bootstrap is already in progress"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );

        assertTrue(
                transferRegistry
                        .findByPlayer(PLAYER_ID)
                        .isEmpty()
        );
    }

    @Test
    void rejectsSpoofedPlayerIdentifier() {
        ProtocolMessageContext context =
                transferContext(OTHER_PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                OTHER_PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Transfer source does not match player"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsUnauthenticatedPlayer() {
        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player is not authenticated"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsPresenceFromDifferentBackend() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );

        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "skyblock-1",
                        NOW - 100
                )
        );

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player presence does not match source backend"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsTargetWithoutAuthenticatedHandshake() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .notAuthenticated()
                );

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Target backend is not authenticated"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void preservesNewTargetPresenceOnSuccessfulCallback() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "skyblock-1",
                        NOW + 100
                )
        );

        future.complete(
                PlayerTransferCompletion.success()
        );

        assertEquals(
                "skyblock-1",
                presenceRegistry
                        .find(PLAYER_ID)
                        .orElseThrow()
                        .backendName()
        );
    }

    @Test
    void lateResultDoesNotRemoveDifferentTransferWithSameRequest() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        CompletableFuture<PlayerTransferCompletion> future =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, target))
                .thenReturn(future);

        ProtocolMessageContext context =
                transferContext(PLAYER_ID);

        handler.handle(context);

        transferRegistry.remove(REQUEST_ID);

        PendingPlayerTransfer newerTransfer =
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        "auth-1",
                        "lobby-1",
                        NOW + 1
                );

        transferRegistry.register(newerTransfer);

        future.complete(
                PlayerTransferCompletion.success()
        );

        assertEquals(
                newerTransfer,
                transferRegistry
                        .findByRequest(REQUEST_ID)
                        .orElseThrow()
        );

        verify(
                resultSender,
                never()
        ).send(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsEnvelopeWithUnexpectedPayload() {
        ProtocolEnvelope<PingPayload> envelope =
                ProtocolEnvelope.create(
                        ProtocolMessageType.TRANSFER_REQUEST,
                        new PingPayload(NOW)
                );

        ProtocolMessageContext context =
                new ProtocolMessageContext(
                        source,
                        envelope
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> handler.handle(context)
        );
    }

    @Test
    void firstColdTargetFailsThenSecondTargetSucceeds() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution
                        .bootstrapRequired(secondTarget)
        );

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.failed()
                        )
                );
        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    @Test
    void firstBootstrapTargetBusyThenSecondTargetSucceeds() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        BackendBootstrapReservation existingReservation =
                new BackendBootstrapReservation(
                        "skyblock-1",
                        UUID.fromString(
                                "22222222-3333-4444-5555-666666666666"
                        ),
                        OTHER_PLAYER_ID,
                        NOW - 1
                );

        bootstrapRegistry.register(existingReservation);

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution
                        .bootstrapRequired(secondTarget)
        );

        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);

        assertEquals(
                existingReservation,
                bootstrapRegistry.findByTarget("skyblock-1").orElseThrow()
        );
        assertEquals(
                REQUEST_ID,
                bootstrapRegistry
                        .findByTarget("skyblock-2")
                        .orElseThrow()
                        .requestId()
        );
        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    @Test
    void allBootstrapTargetsBusySendsOneTerminalResult() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        BackendBootstrapReservation firstExisting =
                new BackendBootstrapReservation(
                        "skyblock-1",
                        UUID.fromString(
                                "22222222-3333-4444-5555-666666666666"
                        ),
                        OTHER_PLAYER_ID,
                        NOW - 1
                );
        BackendBootstrapReservation secondExisting =
                new BackendBootstrapReservation(
                        "skyblock-2",
                        UUID.fromString(
                                "33333333-4444-5555-6666-777777777777"
                        ),
                        UUID.fromString(
                                "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"
                        ),
                        NOW - 1
                );

        bootstrapRegistry.register(firstExisting);
        bootstrapRegistry.register(secondExisting);

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution
                        .bootstrapRequired(secondTarget)
        );
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1", "skyblock-2")
        )).thenReturn(TransferTargetResolution.notConfigured());

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender, times(1)).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Target backend bootstrap is already in progress"
        );
        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        assertEquals(
                firstExisting,
                bootstrapRegistry.findByTarget("skyblock-1").orElseThrow()
        );
        assertEquals(
                secondExisting,
                bootstrapRegistry.findByTarget("skyblock-2").orElseThrow()
        );
    }

    @Test
    void firstResolvedTargetRejectedThenSecondTargetSucceeds() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(TransferTargetResolution.resolved(secondTarget));

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.rejected()
                        )
                );
        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    @Test
    void timedOutTargetDoesNotRetryAnotherTarget() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(TransferTargetResolution.resolved(secondTarget));

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.timedOut()
                        )
                );
        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);

        verify(transferExecutor, times(1)).execute(player, target);
        verify(transferExecutor, never()).execute(player, secondTarget);
        verify(resultSender, times(1)).send(
                context,
                PLAYER_ID,
                TransferResultStatus.TIMED_OUT,
                "Player transfer timed out"
        );
        assertEquals(
                "lobby-1",
                presenceRegistry.find(PLAYER_ID).orElseThrow().backendName()
        );
        assertTrue(transferRegistry.findByRequest(REQUEST_ID).isEmpty());
        assertTrue(bootstrapRegistry.findByRequest(REQUEST_ID).isEmpty());
        verify(targetResolver).releaseCapacity(
                new BackendCapacityReservation(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1"
                )
        );
    }

    @Test
    void firstExecutorThrowThenSecondTargetSucceeds() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(TransferTargetResolution.resolved(secondTarget));

        when(transferExecutor.execute(player, target))
                .thenThrow(new IllegalStateException("internal"));
        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    @Test
    void firstExceptionalCompletionThenSecondTargetSucceeds() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(TransferTargetResolution.resolved(secondTarget));

        CompletableFuture<PlayerTransferCompletion> first =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, target))
                .thenReturn(first);
        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);
        first.completeExceptionally(
                new IllegalStateException("internal")
        );

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    @Test
    void allTargetsFailSendsExactlyOneTerminalResult() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(TransferTargetResolution.notConfigured());

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.failed()
                        )
                );

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);

        verify(resultSender, times(1)).send(
                context,
                PLAYER_ID,
                TransferResultStatus.FAILED,
                "Player transfer failed"
        );
    }

    @Test
    void failedAttemptDoesNotRemoveSourcePresence() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(TransferTargetResolution.notConfigured());

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.failed()
                        )
                );

        handler.handle(transferContext(PLAYER_ID));

        assertEquals(
                "lobby-1",
                presenceRegistry.find(PLAYER_ID).orElseThrow().backendName()
        );
    }

    @Test
    void successfulRetryRemovesOnlyOriginalSourcePresence() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(TransferTargetResolution.resolved(target));
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(TransferTargetResolution.resolved(secondTarget));

        CompletableFuture<PlayerTransferCompletion> first =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, target))
                .thenReturn(first);
        when(transferExecutor.execute(player, secondTarget))
                .thenAnswer(invocation -> {
                    presenceRegistry.update(
                            new PlayerServerPresence(
                                    PLAYER_ID,
                                    "skyblock-2",
                                    NOW + 100
                            )
                    );
                    return CompletableFuture.completedFuture(
                            PlayerTransferCompletion.success()
                    );
                });

        handler.handle(transferContext(PLAYER_ID));
        first.complete(PlayerTransferCompletion.failed());

        assertEquals(
                "skyblock-2",
                presenceRegistry.find(PLAYER_ID).orElseThrow().backendName()
        );
    }

    @Test
    void failedAttemptReleasesCapacityAndBootstrapBeforeRetry() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenAnswer(invocation -> {
            assertTrue(bootstrapRegistry.findByTarget("skyblock-1").isEmpty());
            return TransferTargetResolution.resolved(secondTarget);
        });

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.failed()
                        )
                );
        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        handler.handle(transferContext(PLAYER_ID));

        verify(targetResolver, times(2)).releaseCapacity(any());
    }

    @Test
    void successfulRetryKeepsWinningBootstrapReservation() {
        registerPlayerState();
        RegisteredServer secondTarget = server("skyblock-2");

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(
                        TransferTargetResolution
                                .bootstrapRequired(target)
                );
        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                java.util.Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution
                        .bootstrapRequired(secondTarget)
        );

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.failed()
                        )
                );
        when(transferExecutor.execute(player, secondTarget))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        handler.handle(transferContext(PLAYER_ID));

        assertTrue(bootstrapRegistry.findByTarget("skyblock-1").isEmpty());
        assertEquals(
                REQUEST_ID,
                bootstrapRegistry
                        .findByTarget("skyblock-2")
                        .orElseThrow()
                        .requestId()
        );
    }

    @Test
    void lateCompletionCannotSendDuplicateResultOrRemoveNewerState() {
        registerPlayerState();

        when(targetResolver.resolve(BackendType.SKYBLOCK))
                .thenReturn(TransferTargetResolution.resolved(target));

        CompletableFuture<PlayerTransferCompletion> first =
                new CompletableFuture<>();

        when(transferExecutor.execute(player, target))
                .thenReturn(first);

        ProtocolMessageContext context = transferContext(PLAYER_ID);

        handler.handle(context);

        transferRegistry.remove(REQUEST_ID);
        PendingPlayerTransfer newerTransfer =
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        OTHER_PLAYER_ID,
                        "auth-1",
                        "lobby-1",
                        NOW + 1
                );
        transferRegistry.register(newerTransfer);

        first.complete(PlayerTransferCompletion.failed());

        assertEquals(
                newerTransfer,
                transferRegistry.findByRequest(REQUEST_ID).orElseThrow()
        );
        assertEquals(
                "lobby-1",
                presenceRegistry.find(PLAYER_ID).orElseThrow().backendName()
        );
        verify(resultSender, never()).send(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsNullContext() {
        assertThrows(
                NullPointerException.class,
                () -> handler.handle(null)
        );
    }

    @Test
    void transfersAuthenticatedPlayerFromAuthToLobby() {
        identityRegistry.register(
                new BackendIdentity(
                        "auth-1",
                        BackendType.AUTH
                )
        );

        when(source.getServerInfo().getName())
                .thenReturn("auth-1");

        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );

        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.of(player));

        when(player.getCurrentServer())
                .thenReturn(Optional.of(source));

        when(target.getServerInfo().getName())
                .thenReturn("lobby-1");

        when(targetResolver.resolve(BackendType.LOBBY))
                .thenReturn(
                        TransferTargetResolution.resolved(target)
                );

        when(transferExecutor.execute(player, target))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                PlayerTransferCompletion.success()
                        )
                );

        ProtocolMessageContext context =
                transferContext(
                        PLAYER_ID,
                        BackendType.LOBBY
                );

        handler.handle(context);

        verify(transferExecutor).execute(player, target);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    @Test
    void rejectsTransferFromAuthToAuth() {
        identityRegistry.register(
                new BackendIdentity(
                        "auth-1",
                        BackendType.AUTH
                )
        );

        when(source.getServerInfo().getName())
                .thenReturn("auth-1");

        TransferRequestPayload payload =
                mock(TransferRequestPayload.class);

        when(payload.playerId())
                .thenReturn(PLAYER_ID);

        when(payload.targetBackendType())
                .thenReturn(BackendType.AUTH);

        ProtocolMessageContext context =
                transferContext(payload);

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Transfer is not allowed for source and target backend types"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsTransferFromAuthToSkyblock() {
        identityRegistry.register(
                new BackendIdentity(
                        "auth-1",
                        BackendType.AUTH
                )
        );

        when(source.getServerInfo().getName())
                .thenReturn("auth-1");

        ProtocolMessageContext context =
                transferContext(
                        PLAYER_ID,
                        BackendType.SKYBLOCK
                );

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Transfer is not allowed for source and target backend types"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsUnauthenticatedPlayerFromAuthToLobby() {
        identityRegistry.register(
                new BackendIdentity(
                        "auth-1",
                        BackendType.AUTH
                )
        );

        when(source.getServerInfo().getName())
                .thenReturn("auth-1");

        ProtocolMessageContext context =
                transferContext(
                        PLAYER_ID,
                        BackendType.LOBBY
                );

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player is not authenticated"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsOfflinePlayerFromAuthToLobby() {
        identityRegistry.register(
                new BackendIdentity(
                        "auth-1",
                        BackendType.AUTH
                )
        );

        when(source.getServerInfo().getName())
                .thenReturn("auth-1");

        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );

        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.empty());

        ProtocolMessageContext context =
                transferContext(
                        PLAYER_ID,
                        BackendType.LOBBY
                );

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player connection does not match source backend"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsPlayerConnectedOutsideAuthFromAuthToLobby() {
        identityRegistry.register(
                new BackendIdentity(
                        "auth-1",
                        BackendType.AUTH
                )
        );

        when(source.getServerInfo().getName())
                .thenReturn("auth-1");

        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );

        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.of(player));

        ServerConnection differentConnection =
                mock(ServerConnection.class);

        ServerInfo differentServerInfo =
                mock(ServerInfo.class);

        when(differentConnection.getServerInfo())
                .thenReturn(differentServerInfo);

        when(differentServerInfo.getName())
                .thenReturn("lobby-1");

        when(player.getCurrentServer())
                .thenReturn(Optional.of(differentConnection));

        ProtocolMessageContext context =
                transferContext(
                        PLAYER_ID,
                        BackendType.LOBBY
                );

        handler.handle(context);

        verify(resultSender).send(
                context,
                PLAYER_ID,
                TransferResultStatus.REJECTED,
                "Player connection does not match source backend"
        );

        verify(
                transferExecutor,
                never()
        ).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private void registerPlayerState() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );

        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "lobby-1",
                        NOW - 100
                )
        );

        when(proxyServer.getPlayer(PLAYER_ID))
                .thenReturn(Optional.of(player));

        when(player.getCurrentServer())
                .thenReturn(Optional.of(source));
    }

    private void configureSourceConnection() {
        ServerInfo sourceInfo =
                mock(ServerInfo.class);

        when(source.getServerInfo())
                .thenReturn(sourceInfo);

        when(sourceInfo.getName())
                .thenReturn("lobby-1");

        when(source.getPlayer())
                .thenReturn(player);

        when(player.getUniqueId())
                .thenReturn(PLAYER_ID);
    }

    private void configureTarget() {
        ServerInfo targetInfo =
                mock(ServerInfo.class);

        when(target.getServerInfo())
                .thenReturn(targetInfo);

        when(targetInfo.getName())
                .thenReturn("skyblock-1");
    }

    private RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);

        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);

        return server;
    }

    private ProtocolMessageContext transferContext(
            UUID playerId
    ) {
        return transferContext(
                playerId,
                BackendType.SKYBLOCK
        );
    }

    private ProtocolMessageContext transferContext(
            UUID playerId,
            BackendType targetBackendType
    ) {
        return transferContext(
                new TransferRequestPayload(
                        playerId,
                        targetBackendType
                )
        );
    }

    private ProtocolMessageContext transferContext(
            TransferRequestPayload payload
    ) {
        ProtocolEnvelope<TransferRequestPayload> envelope =
                new ProtocolEnvelope<>(
                        com.theosfera.protocol.ProtocolVersion.CURRENT,
                        ProtocolMessageType.TRANSFER_REQUEST,
                        REQUEST_ID,
                        NOW - 1,
                        payload
                );

        return new ProtocolMessageContext(
                source,
                envelope
        );
    }
}

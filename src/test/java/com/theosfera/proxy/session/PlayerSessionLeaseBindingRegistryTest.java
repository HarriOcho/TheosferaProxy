package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerSessionLeaseBindingRegistryTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
            );

    private static final UUID ACQUISITION_1 =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ACQUISITION_2 =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ACQUISITION_3 =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final ProxyInstanceIdentity OWNER =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString(
                            "d505feca-365c-4fb4-818e-3efccf124d97"
                    )
            );

    private static final ProxyInstanceIdentity OTHER_OWNER =
            new ProxyInstanceIdentity(
                    "proxy-2",
                    UUID.fromString(
                            "7f48ad12-9ccd-47eb-a075-8823e337108a"
                    )
            );

    private final PlayerSessionLeaseBindingRegistry registry =
            new PlayerSessionLeaseBindingRegistry();

    @Test
    void bindsLeaseForExactAcquisition() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

        AuthenticatedPlayerSession session =
                lease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

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

        PlayerSessionLeaseBindingResult bindingResult =
                registry.bind(
                        player,
                        ACQUISITION_1,
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

        assertEquals(
                lease,
                registry.find(player).orElseThrow()
        );
    }

    @Test
    void supportsOverlappingAcquisitions() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

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

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.ALREADY_BOUND,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                lease,
                registry.find(player).orElseThrow()
        );
    }

    @Test
    void cancelsConflictingAcquisitionWithoutRemovingLease() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

        AuthenticatedPlayerSession session = lease.session();

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

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement timeoutAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                registry.claimAcquisitionTimeout(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        session,
                        timeoutAcknowledgement
                );

        assertTrue(cancellation.shouldRespond());
        assertTrue(
                cancellation.leaseToRelease().isEmpty()
        );

        assertEquals(
                lease,
                registry.find(player).orElseThrow()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                registry.beginTracked(
                        player,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                timeoutAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );
    }

    @Test
    void rejectsBindingWithoutMatchingAcquisition() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

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

        PlayerSessionLeaseBindingResult result =
                registry.bind(
                        player,
                        ACQUISITION_1,
                        1L,
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                result
        );

        assertTrue(registry.find(player).isEmpty());
    }

    @Test
    void replacesLeaseOnlyWithHigherFencingToken() {
        Player player = player(PLAYER_ID);

        PlayerSessionLease oldLease =
                lease(OWNER, 1L);
        PlayerSessionLease newerLease =
                lease(OWNER, 2L);

        AuthenticatedPlayerSession oldSession =
                oldLease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        oldSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

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

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        oldSession,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        AuthenticatedPlayerSession newerSession =
                newerLease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_2,
                        newerSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.REPLACED,
                registry.bind(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        newerSession,
                        newerLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                newerLease,
                registry.find(player).orElseThrow()
        );
    }

    @Test
    void rejectsEqualFencingTokenConflict() {
        Player player = player(PLAYER_ID);

        PlayerSessionLease existingLease =
                lease(OWNER, 1L);
        PlayerSessionLease conflictingLease =
                lease(OTHER_OWNER, 1L);

        AuthenticatedPlayerSession existingSession =
                existingLease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        existingSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

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

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        existingSession,
                        existingLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        AuthenticatedPlayerSession conflictingSession =
                conflictingLease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_2,
                        conflictingSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.CONFLICT,
                registry.bind(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        conflictingSession,
                        conflictingLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                existingLease,
                registry.find(player).orElseThrow()
        );
    }

    @Test
    void newConnectionSupersedesOldPendingAcquisition() {
        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        PlayerSessionLease oldLease =
                lease(OWNER, 1L);
        PlayerSessionLease newLease =
                lease(OWNER, 2L);

        AuthenticatedPlayerSession oldSession =
                oldLease.session();
        AuthenticatedPlayerSession newSession =
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

        PlayerSessionLeaseBindingRegistry.BeginResult oldBegin =
                registry.beginTracked(
                        oldConnection,
                        ACQUISITION_1,
                        oldSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult newBegin =
                registry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        newSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                newBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                registry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId(),
                        oldSession,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        newConnection,
                        ACQUISITION_2,
                        newBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        newConnection,
                        ACQUISITION_2,
                        newBegin.attemptId(),
                        newSession,
                        newLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertTrue(
                registry.find(oldConnection).isEmpty()
        );

        assertEquals(
                newLease,
                registry.find(newConnection).orElseThrow()
        );
    }

    @Test
    void pendingDisconnectRejectsLateCallback() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

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
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.removeForDisconnect(player).isEmpty()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingResult result =
                registry.bind(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.DISCONNECTED,
                result
        );

        assertTrue(registry.find(player).isEmpty());
    }

    @Test
    void boundDisconnectReturnsExactLease() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

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
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingResult bindingResult =
                registry.bind(
                        player,
                        ACQUISITION_1,
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

        assertEquals(
                lease,
                registry.removeForDisconnect(
                        player
                ).orElseThrow()
        );

        assertTrue(registry.find(player).isEmpty());
    }

    @Test
    void oldDisconnectDoesNotRemoveNewConnection() {
        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        PlayerSessionLease newLease =
                lease(OWNER, 2L);

        AuthenticatedPlayerSession newSession =
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
                registry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        newSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        newConnection,
                        ACQUISITION_2,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingResult bindingResult =
                registry.bind(
                        newConnection,
                        ACQUISITION_2,
                        begin.attemptId(),
                        newSession,
                        newLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                bindingResult
        );

        assertTrue(
                registry.removeForDisconnect(
                        oldConnection
                ).isEmpty()
        );

        assertTrue(
                registry.find(oldConnection).isEmpty()
        );

        assertEquals(
                newLease,
                registry.find(newConnection).orElseThrow()
        );
    }

    @Test
    void removesOnlyExactPlayerAndLeaseBinding() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

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
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingResult bindingResult =
                registry.bind(
                        player,
                        ACQUISITION_1,
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

        assertTrue(
                registry.removeIfMatches(
                        player,
                        lease(OWNER, 2L)
                ).isEmpty()
        );

        assertEquals(
                lease,
                registry.removeIfMatches(
                        player,
                        lease
                ).orElseThrow()
        );
    }

    @Test
    void oldConnectionCannotReclaimLeaseAfterNewBinding() {
        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        PlayerSessionLease sharedLease =
                lease(OWNER, 1L);

        AuthenticatedPlayerSession session =
                sharedLease.session();

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

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        oldConnection,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        sharedLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                registry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        newConnection,
                        ACQUISITION_2,
                        secondBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.REPLACED,
                registry.bind(
                        newConnection,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        session,
                        sharedLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult thirdBegin =
                registry.beginTracked(
                        oldConnection,
                        ACQUISITION_3,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                thirdBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_3,
                        thirdBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                registry.bind(
                        oldConnection,
                        ACQUISITION_3,
                        thirdBegin.attemptId(),
                        session,
                        sharedLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                sharedLease,
                registry.find(newConnection).orElseThrow()
        );

        assertTrue(
                registry.find(oldConnection).isEmpty()
        );
    }
    @Test
    void failedNewConnectionPreservesOldBoundLease() {
        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        PlayerSessionLease oldLease =
                lease(OWNER, 1L);

        AuthenticatedPlayerSession session = oldLease.session();

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

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        oldConnection,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                registry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement timeoutAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                registry.claimAcquisitionTimeout(
                        newConnection,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        session,
                        timeoutAcknowledgement
                );

        assertTrue(cancellation.shouldRespond());

        assertTrue(
                cancellation.leaseToRelease().isEmpty()
        );

        assertEquals(
                oldLease,
                registry.find(oldConnection).orElseThrow()
        );

        assertTrue(
                registry.find(newConnection).isEmpty()
        );
    }

    @Test
    void oldDisconnectDefersReleaseUntilNewAcquisitionFails() {
        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        PlayerSessionLease oldLease =
                lease(OWNER, 1L);

        AuthenticatedPlayerSession newSession = oldLease.session();

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

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        oldConnection,
                        ACQUISITION_1,
                        newSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        newSession,
                        oldLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                registry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        newSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        assertTrue(
                registry.removeForDisconnect(
                        oldConnection
                ).isEmpty()
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement timeoutAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                registry.claimAcquisitionTimeout(
                        newConnection,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        newSession,
                        timeoutAcknowledgement
                );

        assertTrue(cancellation.shouldRespond());

        assertEquals(
                oldLease,
                cancellation
                        .leaseToRelease()
                        .orElseThrow()
        );

        assertTrue(
                registry.find(oldConnection).isEmpty()
        );

        assertTrue(
                registry.find(newConnection).isEmpty()
        );
    }

    @Test
    void successfulNewConnectionClaimsDeferredLease() {
        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        PlayerSessionLease sharedLease =
                lease(OWNER, 1L);

        AuthenticatedPlayerSession session =
                sharedLease.session();

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

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        oldConnection,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        sharedLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                registry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        assertTrue(
                registry.removeForDisconnect(
                        oldConnection
                ).isEmpty()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        newConnection,
                        ACQUISITION_2,
                        secondBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.REPLACED,
                registry.bind(
                        newConnection,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        session,
                        sharedLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                sharedLease,
                registry.find(newConnection).orElseThrow()
        );

        assertTrue(
                registry.find(oldConnection).isEmpty()
        );
    }
    @Test
    void preservesOriginalReleaseClaimWhenNewerReleaseCompletesFirst() {
        Player player = player(PLAYER_ID);

        PlayerSessionLease originalLease =
                lease(OWNER, 1L);

        PlayerSessionLease newerLease =
                lease(OWNER, 2L);

        AuthenticatedPlayerSession session =
                newerLease.session();

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
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        originalLease
                )
        );

        var originalCompletion =
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).orElseThrow();

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        newerLease
                )
        );

        registry.completeRelease(
                newerLease,
                true
        );

        registry.completeRelease(
                originalLease,
                true
        );

        assertTrue(
                registry.claimReleaseCompletion(
                        player,
                        ACQUISITION_1,
                        originalCompletion
                )
        );

        PlayerSessionLeaseBindingResult result =
                registry.bind(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        newerLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                result
        );

        assertTrue(registry.find(player).isEmpty());

        assertFalse(
                registry.claimReleaseCompletion(
                        player,
                        ACQUISITION_1,
                        originalCompletion
                )
        );
    }

    @Test
    void ignoresCompletedReleaseOwnedByUnexpectedProxy() {
        Player player = player(PLAYER_ID);

        PlayerSessionLease otherOwnerLease =
                lease(OTHER_OWNER, 1L);

        registry.begin(
                player,
                ACQUISITION_1
        );

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        otherOwnerLease
                )
        );

        registry.completeRelease(
                otherOwnerLease,
                true
        );

        assertTrue(
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).isEmpty()
        );

        assertTrue(
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OTHER_OWNER
                ).isPresent()
        );
    }
    @Test
    void waitsOnlyForReleaseOwnedByExpectedProxy() {
        Player player = player(PLAYER_ID);

        PlayerSessionLease otherOwnerLease =
                lease(OTHER_OWNER, 1L);

        registry.begin(
                player,
                ACQUISITION_1
        );

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        otherOwnerLease
                )
        );

        assertTrue(
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).isEmpty()
        );

        assertTrue(
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OTHER_OWNER
                ).isPresent()
        );
    }

    @Test
    void ignoresPendingReleaseAtExclusiveFencingFloor() {
        Player player = player(PLAYER_ID);

        PlayerSessionLease consumedLease =
                lease(OWNER, 1L);

        PlayerSessionLease sameFloorLease =
                new PlayerSessionLease(
                        new AuthenticatedPlayerSession(
                                PLAYER_ID,
                                "HarriOcho",
                                1_750_000_000_001L
                        ),
                        OWNER,
                        1L
                );

        AuthenticatedPlayerSession session =
                sameFloorLease.session();

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
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        consumedLease
                )
        );

        var consumedCompletion =
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).orElseThrow();

        registry.completeRelease(
                consumedLease,
                true
        );

        assertTrue(
                registry.claimReleaseCompletion(
                        player,
                        ACQUISITION_1,
                        consumedCompletion
                )
        );

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        sameFloorLease
                )
        );

        assertTrue(
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).isEmpty()
        );

        PlayerSessionLeaseBindingResult result =
                registry.bind(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        sameFloorLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                result
        );
    }

    @Test
    void claimsReleaseCompletionOnlyOnce() {
        Player player = player(PLAYER_ID);

        PlayerSessionLease lease =
                lease(OWNER, 1L);

        registry.begin(
                player,
                ACQUISITION_1
        );

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        lease
                )
        );

        var completion =
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).orElseThrow();

        assertTrue(
                registry.claimReleaseCompletion(
                        player,
                        ACQUISITION_1,
                        completion
                )
        );

        assertFalse(
                registry.claimReleaseCompletion(
                        player,
                        ACQUISITION_1,
                        completion
                )
        );
    }

    @Test
    void pendingReleaseTimeoutTerminalizesExactWaitAtomically() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);
        AuthenticatedPlayerSession session =
                lease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        lease
                )
        );

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

        assertEquals(
                PlayerSessionLeaseBindingResult.RELEASE_PENDING,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        CompletionStage<Boolean> expectedCompletion =
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).orElseThrow();

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement timeoutAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                registry.claimPendingReleaseTimeout(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        expectedCompletion,
                        timeoutAcknowledgement
                );

        assertTrue(cancellation.shouldRespond());
        assertTrue(cancellation.leaseToRelease().isEmpty());

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                timeoutAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        PlayerSessionLeaseBindingRegistry.Cancellation
                duplicateTimeout =
                registry.claimPendingReleaseTimeout(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        expectedCompletion,
                        timeoutAcknowledgement
                );

        assertFalse(duplicateTimeout.shouldRespond());
        assertTrue(duplicateTimeout.leaseToRelease().isEmpty());

        registry.completeRelease(
                lease,
                true
        );

        assertTrue(
                registry
                        .claimReleaseCompletionAndBeginRetry(
                                player,
                                ACQUISITION_1,
                                begin.attemptId(),
                                expectedCompletion
                        ).isEmpty()
        );

        assertFalse(
                registry.claimReleaseCompletion(
                        player,
                        ACQUISITION_1,
                        expectedCompletion
                )
        );
    }

    @Test
    void pendingReleaseTimeoutRejectsDifferentCompletion() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);
        AuthenticatedPlayerSession session =
                lease.session();

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        assertTrue(
                registry.reserveReleaseIfUnbound(
                        lease
                )
        );

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

        assertEquals(
                PlayerSessionLeaseBindingResult.RELEASE_PENDING,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        CompletionStage<Boolean> expectedCompletion =
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).orElseThrow();

        CompletionStage<Boolean> differentCompletion =
                new CompletableFuture<>();

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement timeoutAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                wrongCompletion =
                registry.claimPendingReleaseTimeout(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        differentCompletion,
                        timeoutAcknowledgement
                );

        assertFalse(wrongCompletion.shouldRespond());
        assertTrue(wrongCompletion.leaseToRelease().isEmpty());

        PlayerSessionLeaseBindingRegistry.BeginResult pendingReplay =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PENDING_REPLAY,
                pendingReplay.decision()
        );

        PlayerSessionLeaseBindingRegistry.Cancellation
                correctCompletion =
                registry.claimPendingReleaseTimeout(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        expectedCompletion,
                        timeoutAcknowledgement
                );

        assertTrue(correctCompletion.shouldRespond());
        assertTrue(correctCompletion.leaseToRelease().isEmpty());

        PlayerSessionLeaseBindingRegistry.BeginResult completedReplay =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                completedReplay.decision()
        );

        assertEquals(
                timeoutAcknowledgement,
                completedReplay.acknowledgement().orElseThrow()
        );
    }

    @Test
    void clearRemovesAllState() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

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
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        registry.clear();

        PlayerSessionLeaseBindingResult result =
                registry.bind(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                result
        );

        assertTrue(registry.find(player).isEmpty());
    }

    @Test
    void rejectsMismatchedPlayerIdentity() {
        Player player = player(UUID.randomUUID());
        PlayerSessionLease incompatibleLease =
                lease(OWNER, 1L);

        AuthenticatedPlayerSession expectedSession =
                new AuthenticatedPlayerSession(
                        player.getUniqueId(),
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        expectedSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

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

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.bind(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        expectedSession,
                        incompatibleLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.removeIfMatches(
                        player,
                        lease(OWNER, 1L)
                )
        );
    }

    @Test
    void timeoutStoresTerminalAcknowledgementAtomically() {
        AtomicLong monotonicTime =
                new AtomicLong(5_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        1,
                        60_000L
                );

        Player player = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement acknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation cancellation =
                boundedRegistry.claimAcquisitionTimeout(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        acknowledgement
                );

        assertTrue(cancellation.shouldRespond());

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
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

        assertFalse(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );
    }

    @Test
    void bindStoresSuccessfulTerminalAcknowledgementAtomically() {
        AtomicLong monotonicTime =
                new AtomicLong(6_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        2,
                        60_000L
                );

        Player player = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session,
                        OWNER,
                        1L
                );

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

        PlayerSessionLeaseBindingResult bindingResult =
                boundedRegistry.bind(
                        player,
                        ACQUISITION_1,
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

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
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
                boundedRegistry.find(player).orElseThrow()
        );

        assertFalse(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );
    }

    @Test
    void atomicBindDisconnectedReleasesTrackedCapacityImmediately() {
        AtomicLong monotonicTime =
                new AtomicLong(7_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        1,
                        60_000L
                );

        Player firstPlayer = player(PLAYER_ID);

        AuthenticatedPlayerSession firstSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                boundedRegistry.beginTracked(
                        firstPlayer,
                        ACQUISITION_1,
                        firstSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        firstPlayer,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        assertTrue(
                boundedRegistry
                        .removeForDisconnect(firstPlayer)
                        .isEmpty()
        );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        firstSession,
                        OWNER,
                        1L
                );

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

        PlayerSessionLeaseBindingResult result =
                boundedRegistry.bind(
                        firstPlayer,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        firstSession,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.DISCONNECTED,
                result
        );

        assertTrue(
                boundedRegistry.find(firstPlayer).isEmpty()
        );

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                boundedRegistry.beginTracked(
                        secondPlayer,
                        ACQUISITION_2,
                        secondSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );
    }

    @Test
    void atomicBindStaleReleasesTrackedCapacityImmediately() {
        AtomicLong monotonicTime =
                new AtomicLong(8_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        1,
                        60_000L
                );

        Player firstPlayer = player(PLAYER_ID);

        AuthenticatedPlayerSession firstSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

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

        PlayerSessionLeaseBindingRegistry.BeginResult initialBegin =
                boundedRegistry.beginTracked(
                        firstPlayer,
                        ACQUISITION_1,
                        firstSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                initialBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        firstPlayer,
                        ACQUISITION_1,
                        initialBegin.attemptId()
                )
        );

        PlayerSessionLease currentLease =
                new PlayerSessionLease(
                        firstSession,
                        OWNER,
                        2L
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                boundedRegistry.bind(
                        firstPlayer,
                        ACQUISITION_1,
                        initialBegin.attemptId(),
                        firstSession,
                        currentLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        monotonicTime.addAndGet(60_000L);

        PlayerSessionLeaseBindingRegistry.BeginResult staleBegin =
                boundedRegistry.beginTracked(
                        firstPlayer,
                        ACQUISITION_2,
                        firstSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                staleBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        firstPlayer,
                        ACQUISITION_2,
                        staleBegin.attemptId()
                )
        );

        PlayerSessionLease staleLease =
                new PlayerSessionLease(
                        firstSession,
                        OWNER,
                        1L
                );

        PlayerSessionLeaseBindingResult staleResult =
                boundedRegistry.bind(
                        firstPlayer,
                        ACQUISITION_2,
                        staleBegin.attemptId(),
                        firstSession,
                        staleLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                staleResult
        );

        assertEquals(
                currentLease,
                boundedRegistry.find(firstPlayer).orElseThrow()
        );

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        UUID thirdAcquisitionId =
                UUID.fromString(
                        "77777777-7777-7777-7777-777777777777"
                );

        PlayerSessionLeaseBindingRegistry.BeginResult nextBegin =
                boundedRegistry.beginTracked(
                        secondPlayer,
                        thirdAcquisitionId,
                        secondSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                nextBegin.decision()
        );
    }

    @Test
    void atomicBindOlderGenerationReleasesTrackedCapacityImmediately() {
        AtomicLong monotonicTime =
                new AtomicLong(9_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        2,
                        60_000L
                );

        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

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
                boundedRegistry.beginTracked(
                        oldConnection,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult newBegin =
                boundedRegistry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                newBegin.decision()
        );

        PlayerSessionLeaseBindingResult staleResult =
                boundedRegistry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId(),
                        session,
                        new PlayerSessionLease(
                                session,
                                OWNER,
                                1L
                        ),
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                staleResult
        );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        session,
                        OWNER,
                        2L
                );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        newConnection,
                        ACQUISITION_2,
                        newBegin.attemptId()
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                boundedRegistry.bind(
                        newConnection,
                        ACQUISITION_2,
                        newBegin.attemptId(),
                        session,
                        newLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertTrue(
                boundedRegistry.find(oldConnection).isEmpty()
        );

        monotonicTime.addAndGet(60_000L);

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstCapacityCheck =
                boundedRegistry.beginTracked(
                        secondPlayer,
                        UUID.fromString(
                                "77777777-7777-7777-7777-777777777777"
                        ),
                        secondSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstCapacityCheck.decision()
        );

        UUID thirdPlayerId =
                UUID.fromString(
                        "88888888-8888-8888-8888-888888888888"
                );

        Player thirdPlayer = player(thirdPlayerId);

        AuthenticatedPlayerSession thirdSession =
                new AuthenticatedPlayerSession(
                        thirdPlayerId,
                        "ThirdPlayer",
                        1_750_000_000_002L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult secondCapacityCheck =
                boundedRegistry.beginTracked(
                        thirdPlayer,
                        UUID.fromString(
                                "99999999-9999-9999-9999-999999999999"
                        ),
                        thirdSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondCapacityCheck.decision()
        );
    }

    @Test
    void atomicBindOlderGenerationAgainstNewerBoundReleasesTrackedCapacityImmediately() {
        AtomicLong monotonicTime =
                new AtomicLong(10_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        2,
                        60_000L
                );

        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

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
                boundedRegistry.beginTracked(
                        oldConnection,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult newBegin =
                boundedRegistry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                newBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        newConnection,
                        ACQUISITION_2,
                        newBegin.attemptId()
                )
        );

        PlayerSessionLease newLease =
                new PlayerSessionLease(
                        session,
                        OWNER,
                        2L
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                boundedRegistry.bind(
                        newConnection,
                        ACQUISITION_2,
                        newBegin.attemptId(),
                        session,
                        newLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertEquals(
                newLease,
                boundedRegistry.find(newConnection).orElseThrow()
        );

        PlayerSessionLeaseBindingResult staleResult =
                boundedRegistry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId(),
                        session,
                        new PlayerSessionLease(
                                session,
                                OWNER,
                                1L
                        ),
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                staleResult
        );

        assertEquals(
                newLease,
                boundedRegistry.find(newConnection).orElseThrow()
        );

        monotonicTime.addAndGet(60_000L);

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstCapacityCheck =
                boundedRegistry.beginTracked(
                        secondPlayer,
                        UUID.fromString(
                                "77777777-7777-7777-7777-777777777777"
                        ),
                        secondSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstCapacityCheck.decision()
        );

        UUID thirdPlayerId =
                UUID.fromString(
                        "88888888-8888-8888-8888-888888888888"
                );

        Player thirdPlayer = player(thirdPlayerId);

        AuthenticatedPlayerSession thirdSession =
                new AuthenticatedPlayerSession(
                        thirdPlayerId,
                        "ThirdPlayer",
                        1_750_000_000_002L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult secondCapacityCheck =
                boundedRegistry.beginTracked(
                        thirdPlayer,
                        UUID.fromString(
                                "99999999-9999-9999-9999-999999999999"
                        ),
                        thirdSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondCapacityCheck.decision()
        );

    }

    @Test
    void atomicBindDisconnectedOldConnectionWithSameActiveLeaseReleasesTrackedCapacityImmediately() {
        AtomicLong monotonicTime =
                new AtomicLong(11_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        2,
                        60_000L
                );

        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

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
                boundedRegistry.beginTracked(
                        oldConnection,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                oldBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult newBegin =
                boundedRegistry.beginTracked(
                        newConnection,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                newBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        newConnection,
                        ACQUISITION_2,
                        newBegin.attemptId()
                )
        );

        PlayerSessionLease sharedLease =
                new PlayerSessionLease(
                        session,
                        OWNER,
                        1L
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                boundedRegistry.bind(
                        newConnection,
                        ACQUISITION_2,
                        newBegin.attemptId(),
                        session,
                        sharedLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        assertTrue(
                boundedRegistry
                        .removeForDisconnect(oldConnection)
                        .isEmpty()
        );

        PlayerSessionLeaseBindingResult staleResult =
                boundedRegistry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId(),
                        session,
                        sharedLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                staleResult
        );

        assertEquals(
                sharedLease,
                boundedRegistry.find(newConnection).orElseThrow()
        );

        assertTrue(
                boundedRegistry.find(oldConnection).isEmpty()
        );

        assertFalse(
                boundedRegistry.claimAcquisitionResult(
                        oldConnection,
                        ACQUISITION_1,
                        oldBegin.attemptId()
                )
        );

        monotonicTime.addAndGet(60_000L);

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstCapacityCheck =
                boundedRegistry.beginTracked(
                        secondPlayer,
                        UUID.fromString(
                                "77777777-7777-7777-7777-777777777777"
                        ),
                        secondSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstCapacityCheck.decision()
        );

        UUID thirdPlayerId =
                UUID.fromString(
                        "88888888-8888-8888-8888-888888888888"
                );

        Player thirdPlayer = player(thirdPlayerId);

        AuthenticatedPlayerSession thirdSession =
                new AuthenticatedPlayerSession(
                        thirdPlayerId,
                        "ThirdPlayer",
                        1_750_000_000_002L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult secondCapacityCheck =
                boundedRegistry.beginTracked(
                        thirdPlayer,
                        UUID.fromString(
                                "99999999-9999-9999-9999-999999999999"
                        ),
                        thirdSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondCapacityCheck.decision()
        );
    }

    @Test
    void atomicBindBelowPendingReleaseMinimumReleasesTrackedCapacityImmediately() {
        AtomicLong monotonicTime =
                new AtomicLong(12_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        1,
                        60_000L
                );

        Player player = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLease consumedLease =
                new PlayerSessionLease(
                        session,
                        OWNER,
                        1L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertEquals(
                1L,
                firstBegin.attemptId()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        assertTrue(
                boundedRegistry.reserveReleaseIfUnbound(
                        consumedLease
                )
        );

        var consumedCompletion =
                boundedRegistry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).orElseThrow();

        boundedRegistry.completeRelease(
                consumedLease,
                true
        );

        long retryAttemptId =
                boundedRegistry
                        .claimReleaseCompletionAndBeginRetry(
                                player,
                                ACQUISITION_1,
                                firstBegin.attemptId(),
                                consumedCompletion
                        ).orElseThrow();

        assertEquals(
                2L,
                retryAttemptId
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        retryAttemptId
                )
        );

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

        PlayerSessionLease staleLease =
                new PlayerSessionLease(
                        session,
                        OWNER,
                        1L
                );

        PlayerSessionLeaseBindingResult staleResult =
                boundedRegistry.bind(
                        player,
                        ACQUISITION_1,
                        retryAttemptId,
                        session,
                        staleLease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                staleResult
        );

        assertTrue(
                boundedRegistry.find(player).isEmpty()
        );

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult capacityCheck =
                boundedRegistry.beginTracked(
                        secondPlayer,
                        ACQUISITION_2,
                        secondSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                capacityCheck.decision()
        );
    }

    @Test
    void terminalCompletionStoresAcknowledgementAtomicallyAfterResultClaim() {
        AtomicLong monotonicTime =
                new AtomicLong(6_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        2,
                        60_000L
                );

        Player player = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult begin =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                begin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement acknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation completion =
                boundedRegistry.completeTerminalRequest(
                        player,
                        ACQUISITION_1,
                        begin.attemptId(),
                        session,
                        acknowledgement
                );

        assertTrue(completion.shouldRespond());

        assertFalse(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        begin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
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

        PlayerSessionLeaseBindingRegistry.Cancellation staleCompletion =
                boundedRegistry.completeTerminalRequest(
                        player,
                        ACQUISITION_1,
                        begin.attemptId() + 1,
                        session,
                        acknowledgement
                );

        assertFalse(staleCompletion.shouldRespond());

        PlayerSessionLeaseBindingRegistry.BeginResult replayAfterStale =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replayAfterStale.decision()
        );

        assertEquals(
                acknowledgement,
                replayAfterStale.acknowledgement().orElseThrow()
        );
    }

    @Test
    void staleTimeoutCannotCancelRetryAttempt() {
        Player player = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                registry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        PlayerSessionLease lease = lease(OWNER, 1L);

        assertTrue(
                registry.reserveReleaseIfUnbound(lease)
        );

        var releaseCompletion =
                registry.awaitPendingRelease(
                        player,
                        ACQUISITION_1,
                        OWNER
                ).orElseThrow();

        registry.completeRelease(
                lease,
                true
        );

        long retryAttemptId =
                registry.claimReleaseCompletionAndBeginRetry(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        releaseCompletion
                ).orElseThrow();

        assertTrue(
                retryAttemptId != firstBegin.attemptId()
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement timeoutAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                staleTimeout =
                registry.claimAcquisitionTimeout(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        timeoutAcknowledgement
                );

        assertFalse(staleTimeout.shouldRespond());

        assertTrue(
                staleTimeout.leaseToRelease().isEmpty()
        );

        assertTrue(
                registry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        retryAttemptId
                )
        );
    }

    @Test
    void failsClosedInsteadOfEvictingUnexpiredTerminalRequests() {
        AtomicLong currentTimeMillis =
                new AtomicLong(1_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        currentTimeMillis::get,
                        2,
                        60_000L
                );

        Player player = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement terminalAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                firstCompletion =
                boundedRegistry.completeTerminalRequest(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        terminalAcknowledgement
                );

        assertTrue(firstCompletion.shouldRespond());

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry.Cancellation
                secondCompletion =
                boundedRegistry.completeTerminalRequest(
                        player,
                        ACQUISITION_2,
                        secondBegin.attemptId(),
                        session,
                        terminalAcknowledgement
                );

        assertTrue(secondCompletion.shouldRespond());

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.CAPACITY_EXHAUSTED,
                boundedRegistry.beginTracked(
                        secondPlayer,
                        ACQUISITION_3,
                        secondSession
                ).decision()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult firstReplay =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                firstReplay.decision()
        );

        assertEquals(
                terminalAcknowledgement,
                firstReplay.acknowledgement().orElseThrow()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult secondReplay =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_2,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                secondReplay.decision()
        );

        assertEquals(
                terminalAcknowledgement,
                secondReplay.acknowledgement().orElseThrow()
        );

        AuthenticatedPlayerSession conflictingSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_001L
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.CONFLICT,
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        conflictingSession
                ).decision()
        );
    }

    @Test
    void releasesTerminalCapacityOnlyAfterFullReplayWindow() {
        AtomicLong currentTimeMillis =
                new AtomicLong(2_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        currentTimeMillis::get,
                        1,
                        60_000L
                );

        Player player = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult beginResult =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                beginResult.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        beginResult.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement terminalAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation completion =
                boundedRegistry.completeTerminalRequest(
                        player,
                        ACQUISITION_1,
                        beginResult.attemptId(),
                        session,
                        terminalAcknowledgement
                );

        assertTrue(completion.shouldRespond());

        currentTimeMillis.addAndGet(59_999L);

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                terminalAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.CAPACITY_EXHAUSTED,
                boundedRegistry.beginTracked(
                        secondPlayer,
                        ACQUISITION_2,
                        secondSession
                ).decision()
        );

        currentTimeMillis.incrementAndGet();

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                boundedRegistry.beginTracked(
                        secondPlayer,
                        ACQUISITION_2,
                        secondSession
                ).decision()
        );
    }

    @Test
    void atomicTerminalAcknowledgementSurvivesIntermediatePurgeAndExpiresFromCompletion() {
        AtomicLong monotonicTime =
                new AtomicLong(3_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        2,
                        60_000L
                );

        Player firstPlayer = player(PLAYER_ID);

        AuthenticatedPlayerSession firstSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLease lease =
                new PlayerSessionLease(
                        firstSession,
                        OWNER,
                        1L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                boundedRegistry.beginTracked(
                        firstPlayer,
                        ACQUISITION_1,
                        firstSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        firstPlayer,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

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

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                boundedRegistry.bind(
                        firstPlayer,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        firstSession,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                )
        );

        monotonicTime.addAndGet(59_999L);

        UUID secondPlayerId =
                UUID.fromString(
                        "66666666-6666-6666-6666-666666666666"
                );

        Player secondPlayer = player(secondPlayerId);

        AuthenticatedPlayerSession secondSession =
                new AuthenticatedPlayerSession(
                        secondPlayerId,
                        "SecondPlayer",
                        1_750_000_000_001L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult unrelatedBegin =
                boundedRegistry.beginTracked(
                        secondPlayer,
                        ACQUISITION_2,
                        secondSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                unrelatedBegin.decision()
        );

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                boundedRegistry.beginTracked(
                        firstPlayer,
                        ACQUISITION_1,
                        firstSession
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

        monotonicTime.addAndGet(1L);

        PlayerSessionLeaseBindingRegistry.BeginResult afterExpiration =
                boundedRegistry.beginTracked(
                        firstPlayer,
                        ACQUISITION_1,
                        firstSession
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                afterExpiration.decision()
        );
    }

    @Test
    void rejectsLateAcknowledgementFromReplacedAttempt() {
        AtomicLong monotonicTime =
                new AtomicLong(4_000_000L);

        PlayerSessionLeaseBindingRegistry boundedRegistry =
                new PlayerSessionLeaseBindingRegistry(
                        monotonicTime::get,
                        1,
                        60_000L
                );

        Player player = player(PLAYER_ID);

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                );

        PlayerSessionLeaseBindingRegistry.BeginResult firstBegin =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                firstBegin.decision()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement firstAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "First attempt terminal"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                firstCompletion =
                boundedRegistry.completeTerminalRequest(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        firstAcknowledgement
                );

        assertTrue(firstCompletion.shouldRespond());

        monotonicTime.addAndGet(60_000L);

        PlayerSessionLeaseBindingRegistry.BeginResult secondBegin =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.PROCEED,
                secondBegin.decision()
        );

        assertTrue(
                secondBegin.attemptId()
                        > firstBegin.attemptId()
        );

        assertTrue(
                boundedRegistry.claimAcquisitionResult(
                        player,
                        ACQUISITION_1,
                        secondBegin.attemptId()
                )
        );

        PlayerSessionLeaseBindingRegistry.Cancellation
                staleCompletion =
                boundedRegistry.completeTerminalRequest(
                        player,
                        ACQUISITION_1,
                        firstBegin.attemptId(),
                        session,
                        new PlayerSessionLeaseBindingRegistry
                                .TerminalAcknowledgement(
                                false,
                                "Late first attempt"
                        )
                );

        assertFalse(staleCompletion.shouldRespond());

        assertTrue(
                staleCompletion.leaseToRelease().isEmpty()
        );

        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement secondAcknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        true,
                        "Second attempt terminal"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                secondCompletion =
                boundedRegistry.completeTerminalRequest(
                        player,
                        ACQUISITION_1,
                        secondBegin.attemptId(),
                        session,
                        secondAcknowledgement
                );

        assertTrue(secondCompletion.shouldRespond());

        PlayerSessionLeaseBindingRegistry.BeginResult replay =
                boundedRegistry.beginTracked(
                        player,
                        ACQUISITION_1,
                        session
                );

        assertEquals(
                PlayerSessionLeaseBindingRegistry
                        .BeginDecision.COMPLETED_REPLAY,
                replay.decision()
        );

        assertEquals(
                secondAcknowledgement,
                replay.acknowledgement().orElseThrow()
        );

        assertFalse(
                replay.acknowledgement()
                        .orElseThrow()
                        .equals(
                                new PlayerSessionLeaseBindingRegistry
                                        .TerminalAcknowledgement(
                                        false,
                                        "Late first attempt"
                                )
                        )
        );
    }

    @Test
    void rejectsNullArguments() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);
        AuthenticatedPlayerSession session =
                lease.session();
        CompletionStage<Boolean> completion =
                new CompletableFuture<>();
        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement acknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        assertThrows(
                NullPointerException.class,
                () -> registry.begin(
                        null,
                        ACQUISITION_1
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.begin(player, null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.removeForDisconnect(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.claimPendingReleaseTimeout(
                        null,
                        ACQUISITION_1,
                        1L,
                        session,
                        completion,
                        acknowledgement
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.claimPendingReleaseTimeout(
                        player,
                        null,
                        1L,
                        session,
                        completion,
                        acknowledgement
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.claimPendingReleaseTimeout(
                        player,
                        ACQUISITION_1,
                        1L,
                        null,
                        completion,
                        acknowledgement
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.claimPendingReleaseTimeout(
                        player,
                        ACQUISITION_1,
                        1L,
                        session,
                        null,
                        acknowledgement
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.claimPendingReleaseTimeout(
                        player,
                        ACQUISITION_1,
                        1L,
                        session,
                        completion,
                        null
                )
        );
    }

    @Test
    void rejectsInvalidPendingReleaseTimeoutAttemptId() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);
        AuthenticatedPlayerSession session =
                lease.session();
        CompletionStage<Boolean> completion =
                new CompletableFuture<>();
        PlayerSessionLeaseBindingRegistry
                .TerminalAcknowledgement acknowledgement =
                new PlayerSessionLeaseBindingRegistry
                        .TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.claimPendingReleaseTimeout(
                        player,
                        ACQUISITION_1,
                        0L,
                        session,
                        completion,
                        acknowledgement
                )
        );
    }

    private Player player(UUID playerId) {
        Player player = mock(Player.class);

        when(player.getUniqueId()).thenReturn(playerId);

        return player;
    }

    private PlayerSessionLease lease(
            ProxyInstanceIdentity owner,
            long fencingToken
    ) {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                ),
                owner,
                fencingToken
        );
    }
}

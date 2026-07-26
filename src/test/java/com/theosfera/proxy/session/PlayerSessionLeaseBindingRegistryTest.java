package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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

        registry.begin(player, ACQUISITION_1);

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        lease
                )
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

        registry.begin(player, ACQUISITION_1);
        registry.begin(player, ACQUISITION_2);

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        player,
                        ACQUISITION_2,
                        lease
                )
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.ALREADY_BOUND,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        lease
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

        registry.begin(player, ACQUISITION_1);
        registry.bind(
                player,
                ACQUISITION_1,
                lease
        );

        registry.begin(player, ACQUISITION_2);

        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                registry.cancel(
                        player,
                        ACQUISITION_2
                );

        assertTrue(cancellation.shouldRespond());
        assertTrue(
                cancellation.leaseToRelease().isEmpty()
        );

        assertEquals(
                lease,
                registry.find(player).orElseThrow()
        );
    }

    @Test
    void rejectsBindingWithoutMatchingAcquisition() {
        Player player = player(PLAYER_ID);

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        lease(OWNER, 1L)
                )
        );
    }

    @Test
    void replacesLeaseOnlyWithHigherFencingToken() {
        Player player = player(PLAYER_ID);

        registry.begin(player, ACQUISITION_1);
        registry.bind(
                player,
                ACQUISITION_1,
                lease(OWNER, 1L)
        );

        registry.begin(player, ACQUISITION_2);

        PlayerSessionLease newer =
                lease(OWNER, 2L);

        assertEquals(
                PlayerSessionLeaseBindingResult.REPLACED,
                registry.bind(
                        player,
                        ACQUISITION_2,
                        newer
                )
        );

        assertEquals(
                newer,
                registry.find(player).orElseThrow()
        );
    }

    @Test
    void rejectsEqualFencingTokenConflict() {
        Player player = player(PLAYER_ID);

        registry.begin(player, ACQUISITION_1);
        registry.bind(
                player,
                ACQUISITION_1,
                lease(OWNER, 1L)
        );

        registry.begin(player, ACQUISITION_2);

        assertEquals(
                PlayerSessionLeaseBindingResult.CONFLICT,
                registry.bind(
                        player,
                        ACQUISITION_2,
                        lease(OTHER_OWNER, 1L)
                )
        );
    }

    @Test
    void newConnectionSupersedesOldPendingAcquisition() {
        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        registry.begin(
                oldConnection,
                ACQUISITION_1
        );

        registry.begin(
                newConnection,
                ACQUISITION_2
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                registry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        lease(OWNER, 1L)
                )
        );

        PlayerSessionLease newLease =
                lease(OWNER, 2L);

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        newConnection,
                        ACQUISITION_2,
                        newLease
                )
        );

        assertEquals(
                newLease,
                registry.find(newConnection).orElseThrow()
        );
    }

    @Test
    void pendingDisconnectRejectsLateCallback() {
        Player player = player(PLAYER_ID);

        registry.begin(player, ACQUISITION_1);

        assertTrue(
                registry.removeForDisconnect(player).isEmpty()
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.DISCONNECTED,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        lease(OWNER, 1L)
                )
        );

        assertTrue(registry.find(player).isEmpty());
    }

    @Test
    void boundDisconnectReturnsExactLease() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

        registry.begin(player, ACQUISITION_1);
        registry.bind(
                player,
                ACQUISITION_1,
                lease
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

        registry.begin(
                newConnection,
                ACQUISITION_2
        );

        PlayerSessionLease newLease =
                lease(OWNER, 2L);

        registry.bind(
                newConnection,
                ACQUISITION_2,
                newLease
        );

        assertTrue(
                registry.removeForDisconnect(
                        oldConnection
                ).isEmpty()
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

        registry.begin(player, ACQUISITION_1);
        registry.bind(
                player,
                ACQUISITION_1,
                lease
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

        registry.begin(
                oldConnection,
                ACQUISITION_1
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.BOUND,
                registry.bind(
                        oldConnection,
                        ACQUISITION_1,
                        sharedLease
                )
        );

        registry.begin(
                newConnection,
                ACQUISITION_2
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.REPLACED,
                registry.bind(
                        newConnection,
                        ACQUISITION_2,
                        sharedLease
                )
        );

        registry.begin(
                oldConnection,
                ACQUISITION_3
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                registry.bind(
                        oldConnection,
                        ACQUISITION_3,
                        sharedLease
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

        registry.begin(
                oldConnection,
                ACQUISITION_1
        );

        registry.bind(
                oldConnection,
                ACQUISITION_1,
                oldLease
        );

        registry.begin(
                newConnection,
                ACQUISITION_2
        );

        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                registry.cancel(
                        newConnection,
                        ACQUISITION_2
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

        registry.begin(
                oldConnection,
                ACQUISITION_1
        );

        registry.bind(
                oldConnection,
                ACQUISITION_1,
                oldLease
        );

        registry.begin(
                newConnection,
                ACQUISITION_2
        );

        assertTrue(
                registry.removeForDisconnect(
                        oldConnection
                ).isEmpty()
        );

        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                registry.cancel(
                        newConnection,
                        ACQUISITION_2
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

        registry.begin(
                oldConnection,
                ACQUISITION_1
        );

        registry.bind(
                oldConnection,
                ACQUISITION_1,
                sharedLease
        );

        registry.begin(
                newConnection,
                ACQUISITION_2
        );

        assertTrue(
                registry.removeForDisconnect(
                        oldConnection
                ).isEmpty()
        );

        assertEquals(
                PlayerSessionLeaseBindingResult.REPLACED,
                registry.bind(
                        newConnection,
                        ACQUISITION_2,
                        sharedLease
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

        registry.begin(
                player,
                ACQUISITION_1
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

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        newerLease
                )
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

        registry.begin(
                player,
                ACQUISITION_1
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

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        sameFloorLease
                )
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
    void clearRemovesAllState() {
        Player player = player(PLAYER_ID);

        registry.begin(player, ACQUISITION_1);
        registry.clear();

        assertEquals(
                PlayerSessionLeaseBindingResult.STALE,
                registry.bind(
                        player,
                        ACQUISITION_1,
                        lease(OWNER, 1L)
                )
        );
    }

    @Test
    void rejectsMismatchedPlayerIdentity() {
        Player player = player(UUID.randomUUID());

        registry.begin(player, ACQUISITION_1);

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.bind(
                        player,
                        ACQUISITION_1,
                        lease(OWNER, 1L)
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
    void rejectsNullArguments() {
        Player player = player(PLAYER_ID);
        PlayerSessionLease lease = lease(OWNER, 1L);

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
                () -> registry.bind(
                        null,
                        ACQUISITION_1,
                        lease
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.bind(
                        player,
                        null,
                        lease
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.bind(
                        player,
                        ACQUISITION_1,
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.cancel(
                        player,
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> registry.removeForDisconnect(null)
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

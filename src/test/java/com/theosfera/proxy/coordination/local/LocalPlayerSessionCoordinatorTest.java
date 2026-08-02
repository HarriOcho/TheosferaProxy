package com.theosfera.proxy.coordination.local;

import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.PlayerSessionRenewResult;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPlayerSessionCoordinatorTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "417e98b4-74a1-467e-b453-a15be3af8996"
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

    private final AuthenticatedPlayerSessionRegistry registry =
            new AuthenticatedPlayerSessionRegistry();

    private final LocalPlayerSessionCoordinator coordinator =
            new LocalPlayerSessionCoordinator(registry);

    @Test
    void acquiresSessionAndRegistersItLocally() {
        PlayerSessionAcquireResult result =
                acquire(session(), OWNER);

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                result.status()
        );

        PlayerSessionLease lease =
                result.lease().orElseThrow();

        assertEquals(1L, lease.fencingToken());
        assertEquals(OWNER, lease.owner());
        assertEquals(
                lease.session(),
                registry.find(PLAYER_ID).orElseThrow()
        );
    }

    @Test
    void treatsExactRepeatedAcquireAsIdempotent() {
        AuthenticatedPlayerSession session = session();

        PlayerSessionLease first =
                acquire(session, OWNER)
                        .lease()
                        .orElseThrow();

        PlayerSessionAcquireResult repeated =
                acquire(session, OWNER);

        assertEquals(
                PlayerSessionAcquireResult.Status.ALREADY_OWNED,
                repeated.status()
        );
        assertEquals(
                first,
                repeated.lease().orElseThrow()
        );
    }

    @Test
    void rejectsOwnershipByAnotherProxy() {
        AuthenticatedPlayerSession session = session();

        PlayerSessionLease original =
                acquire(session, OWNER)
                        .lease()
                        .orElseThrow();

        PlayerSessionAcquireResult result =
                acquire(session, OTHER_OWNER);

        assertEquals(
                PlayerSessionAcquireResult.Status
                        .OWNED_BY_OTHER_PROXY,
                result.status()
        );
        assertTrue(result.lease().isEmpty());
        assertEquals(
                original.session(),
                registry.find(PLAYER_ID).orElseThrow()
        );
    }

    @Test
    void rejectsConflictingSessionFromSameOwner() {
        acquire(session(), OWNER);

        PlayerSessionAcquireResult result =
                acquire(
                        new AuthenticatedPlayerSession(
                                PLAYER_ID,
                                "HarriOcho",
                                1_750_000_000_025L
                        ),
                        OWNER
                );

        assertEquals(
                PlayerSessionAcquireResult.Status.CONFLICT,
                result.status()
        );
    }

    @Test
    void acquiresLeaseForEquivalentPreexistingLocalSession() {
        AuthenticatedPlayerSession session = session();
        registry.register(session);

        PlayerSessionAcquireResult result =
                acquire(session, OWNER);

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                result.status()
        );
        assertEquals(
                session,
                result.lease().orElseThrow().session()
        );
    }

    @Test
    void renewsOnlyExactLease() {
        PlayerSessionLease lease =
                acquire(session(), OWNER)
                        .lease()
                        .orElseThrow();

        PlayerSessionRenewResult renewed =
                coordinator.renew(lease)
                        .toCompletableFuture()
                        .join();

        assertEquals(
                PlayerSessionRenewResult.Status.RENEWED,
                renewed.status()
        );
        assertEquals(
                lease,
                renewed.lease().orElseThrow()
        );

        PlayerSessionLease stale =
                new PlayerSessionLease(
                        lease.session(),
                        lease.owner(),
                        lease.fencingToken() + 1L
                );

        PlayerSessionRenewResult conflict =
                coordinator.renew(stale)
                        .toCompletableFuture()
                        .join();

        assertEquals(
                PlayerSessionRenewResult.Status.CONFLICT,
                conflict.status()
        );
    }

    @Test
    void distinguishesMissingLeaseAndDifferentOwner() {
        PlayerSessionLease absent =
                new PlayerSessionLease(
                        session(),
                        OWNER,
                        1L
                );

        assertEquals(
                PlayerSessionRenewResult.Status.NOT_FOUND,
                coordinator.renew(absent)
                        .toCompletableFuture()
                        .join()
                        .status()
        );

        PlayerSessionLease owned =
                acquire(session(), OWNER)
                        .lease()
                        .orElseThrow();

        PlayerSessionLease otherOwner =
                new PlayerSessionLease(
                        owned.session(),
                        OTHER_OWNER,
                        owned.fencingToken()
                );

        assertEquals(
                PlayerSessionRenewResult.Status.NOT_OWNER,
                coordinator.renew(otherOwner)
                        .toCompletableFuture()
                        .join()
                        .status()
        );
    }

    @Test
    void releasesOnlyExactOwnedLease() {
        PlayerSessionLease lease =
                acquire(session(), OWNER)
                        .lease()
                        .orElseThrow();

        PlayerSessionLease stale =
                new PlayerSessionLease(
                        lease.session(),
                        lease.owner(),
                        lease.fencingToken() + 1L
                );

        assertFalse(
                coordinator.releaseIfOwned(stale)
                        .toCompletableFuture()
                        .join()
        );
        assertTrue(registry.isAuthenticated(PLAYER_ID));

        assertTrue(
                coordinator.releaseIfOwned(lease)
                        .toCompletableFuture()
                        .join()
        );
        assertFalse(registry.isAuthenticated(PLAYER_ID));

        assertFalse(
                coordinator.releaseIfOwned(lease)
                        .toCompletableFuture()
                .join()
        );
    }

    @Test
    void revokedLocalSessionDoesNotPreventExactCoordinatorRelease() {
        AuthenticatedPlayerSession session = session();

        PlayerSessionLease lease =
                acquire(session, OWNER)
                        .lease()
                        .orElseThrow();

        assertTrue(
                registry.removeIfMatches(session)
                        .isPresent()
        );

        assertTrue(
                coordinator.releaseIfOwned(lease)
                        .toCompletableFuture()
                        .join()
        );

        assertEquals(
                PlayerSessionRenewResult.Status.NOT_FOUND,
                coordinator.renew(lease)
                        .toCompletableFuture()
                        .join()
                        .status()
        );

        PlayerSessionAcquireResult reacquired =
                acquire(session, OWNER);

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                reacquired.status()
        );
    }

    @Test
    void staleLeaseCannotReleaseCurrentCoordinatorLease() {
        AuthenticatedPlayerSession session = session();

        PlayerSessionLease stale =
                acquire(session, OWNER)
                        .lease()
                        .orElseThrow();

        assertTrue(
                coordinator.releaseIfOwned(stale)
                        .toCompletableFuture()
                        .join()
        );

        PlayerSessionLease current =
                acquire(session, OWNER)
                        .lease()
                        .orElseThrow();

        assertFalse(
                coordinator.releaseIfOwned(stale)
                        .toCompletableFuture()
                        .join()
        );

        PlayerSessionRenewResult renewed =
                coordinator.renew(current)
                        .toCompletableFuture()
                        .join();

        assertEquals(
                PlayerSessionRenewResult.Status.RENEWED,
                renewed.status()
        );
        assertEquals(
                current,
                renewed.lease().orElseThrow()
        );
    }

    @Test
    void createsHigherFencingTokenAfterReacquire() {
        PlayerSessionLease first =
                acquire(session(), OWNER)
                        .lease()
                        .orElseThrow();

        assertTrue(
                coordinator.releaseIfOwned(first)
                        .toCompletableFuture()
                        .join()
        );

        PlayerSessionLease second =
                acquire(session(), OWNER)
                        .lease()
                        .orElseThrow();

        assertTrue(
                second.fencingToken()
                        > first.fencingToken()
        );
    }

    @Test
    void returnsAlreadyCompletedStages() {
        CompletionStage<PlayerSessionAcquireResult> stage =
                coordinator.acquire(
                        new PlayerSessionLeaseRequest(
                                session(),
                                OWNER
                        )
                );

        assertTrue(stage.toCompletableFuture().isDone());
    }

    @Test
    void rejectsNullOperations() {
        assertThrows(
                NullPointerException.class,
                () -> coordinator.acquire(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> coordinator.renew(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> coordinator.releaseIfOwned(null)
        );
    }

    private PlayerSessionAcquireResult acquire(
            AuthenticatedPlayerSession session,
            ProxyInstanceIdentity owner
    ) {
        return coordinator.acquire(
                new PlayerSessionLeaseRequest(
                        session,
                        owner
                )
        ).toCompletableFuture().join();
    }

    private AuthenticatedPlayerSession session() {
        return new AuthenticatedPlayerSession(
                PLAYER_ID,
                "HarriOcho",
                1_750_000_000_000L
        );
    }
}

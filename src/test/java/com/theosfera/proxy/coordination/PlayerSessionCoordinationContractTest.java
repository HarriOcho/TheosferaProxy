package com.theosfera.proxy.coordination;

import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSessionCoordinationContractTest {

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

    @Test
    void createsLeaseRequest() {
        AuthenticatedPlayerSession session = session();

        PlayerSessionLeaseRequest request =
                new PlayerSessionLeaseRequest(
                        session,
                        OWNER
                );

        assertEquals(session, request.session());
        assertEquals(OWNER, request.owner());
    }

    @Test
    void rejectsInvalidLeaseRequest() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerSessionLeaseRequest(
                        null,
                        OWNER
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerSessionLeaseRequest(
                        session(),
                        null
                )
        );
    }

    @Test
    void createsFencedLease() {
        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session(),
                        OWNER,
                        15L
                );

        assertEquals(15L, lease.fencingToken());
    }

    @Test
    void rejectsInvalidLease() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerSessionLease(
                        null,
                        OWNER,
                        1L
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerSessionLease(
                        session(),
                        null,
                        1L
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerSessionLease(
                        session(),
                        OWNER,
                        0L
                )
        );
    }

    @Test
    void requiresLeaseForSuccessfulAcquireResults() {
        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session(),
                        OWNER,
                        1L
                );

        PlayerSessionAcquireResult acquired =
                PlayerSessionAcquireResult.acquired(lease);

        PlayerSessionAcquireResult alreadyOwned =
                PlayerSessionAcquireResult.alreadyOwned(lease);

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                acquired.status()
        );
        assertEquals(
                lease,
                acquired.lease().orElseThrow()
        );
        assertEquals(
                PlayerSessionAcquireResult.Status.ALREADY_OWNED,
                alreadyOwned.status()
        );
        assertEquals(
                lease,
                alreadyOwned.lease().orElseThrow()
        );
    }

    @Test
    void rejectsAcquireResultWithInvalidLeasePresence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerSessionAcquireResult(
                        PlayerSessionAcquireResult.Status.ACQUIRED,
                        Optional.empty()
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerSessionAcquireResult(
                        PlayerSessionAcquireResult.Status.CONFLICT,
                        Optional.of(
                                new PlayerSessionLease(
                                        session(),
                                        OWNER,
                                        1L
                                )
                        )
                )
        );
    }

    @Test
    void representsAcquireFailureWithoutLease() {
        PlayerSessionAcquireResult result =
                PlayerSessionAcquireResult.withoutLease(
                        PlayerSessionAcquireResult.Status
                                .OWNED_BY_OTHER_PROXY
                );

        assertEquals(
                PlayerSessionAcquireResult.Status
                        .OWNED_BY_OTHER_PROXY,
                result.status()
        );
        assertTrue(result.lease().isEmpty());
    }

    @Test
    void requiresLeaseOnlyForSuccessfulRenewal() {
        PlayerSessionLease lease =
                new PlayerSessionLease(
                        session(),
                        OWNER,
                        1L
                );

        PlayerSessionRenewResult renewed =
                PlayerSessionRenewResult.renewed(lease);

        PlayerSessionRenewResult notOwner =
                PlayerSessionRenewResult.withoutLease(
                        PlayerSessionRenewResult.Status.NOT_OWNER
                );

        assertEquals(
                lease,
                renewed.lease().orElseThrow()
        );
        assertTrue(notOwner.lease().isEmpty());

        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerSessionRenewResult(
                        PlayerSessionRenewResult.Status.RENEWED,
                        Optional.empty()
                )
        );
    }

    private AuthenticatedPlayerSession session() {
        return new AuthenticatedPlayerSession(
                PLAYER_ID,
                "HarriOcho",
                1_750_000_000_000L
        );
    }
}

package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendCapacityHandoffRegistryTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID INCARNATION_ID = UUID.randomUUID();

    @Test
    void acceptsExactReplayButRejectsRequestAndPlayerConflicts() {
        BackendCapacityHandoffRegistry registry =
                new BackendCapacityHandoffRegistry();
        PlayerSessionLease lease = lease(PLAYER_ID, 7L);
        UUID requestId = UUID.randomUUID();
        BackendCapacityReserveRequest request = request(
                requestId,
                lease,
                "lobby-1"
        );

        assertEquals(
                BackendCapacityHandoffRegistrationResult.REGISTERED,
                registry.register(request)
        );
        assertEquals(
                BackendCapacityHandoffRegistrationResult.ALREADY_REGISTERED,
                registry.register(request)
        );

        PlayerSessionLease otherPlayerLease = lease(UUID.randomUUID(), 1L);
        assertEquals(
                BackendCapacityHandoffRegistrationResult.REQUEST_ID_CONFLICT,
                registry.register(
                        request(requestId, otherPlayerLease, "lobby-1")
                )
        );

        assertEquals(
                BackendCapacityHandoffRegistrationResult.PLAYER_BUSY,
                registry.register(
                        request(UUID.randomUUID(), lease, "skyblock-1")
                )
        );
        assertEquals(1, registry.size());
    }

    @Test
    void disconnectRemovalRequiresExactSessionLease() {
        BackendCapacityHandoffRegistry registry =
                new BackendCapacityHandoffRegistry();
        PlayerSessionLease activeLease = lease(PLAYER_ID, 7L);
        BackendCapacityReserveRequest request = request(
                UUID.randomUUID(),
                activeLease,
                "lobby-1"
        );
        registry.register(request);

        PlayerSessionLease staleLease = lease(PLAYER_ID, 6L);

        assertTrue(
                registry.removeForSessionLease(staleLease).isEmpty()
        );
        assertEquals(1, registry.size());

        assertEquals(
                request,
                registry.removeForSessionLease(activeLease).orElseThrow()
        );
        assertEquals(0, registry.size());
    }

    private PlayerSessionLease lease(UUID playerId, long fencingToken) {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        playerId,
                        "HarriOcho",
                        1_000L
                ),
                new ProxyInstanceIdentity(
                        "proxy-1",
                        INCARNATION_ID
                ),
                fencingToken
        );
    }

    private BackendCapacityReserveRequest request(
            UUID requestId,
            PlayerSessionLease lease,
            String backendName
    ) {
        return new BackendCapacityReserveRequest(
                new BackendCapacityReservation(
                        requestId,
                        lease.session().playerId(),
                        backendName
                ),
                lease
        );
    }
}

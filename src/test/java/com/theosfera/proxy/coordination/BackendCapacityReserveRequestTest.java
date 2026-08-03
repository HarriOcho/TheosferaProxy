package com.theosfera.proxy.coordination;

import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.transfer.BackendCapacityReservation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendCapacityReserveRequestTest {

    @Test
    void acceptsReservationBoundToSamePlayerLease() {
        UUID playerId = UUID.randomUUID();
        BackendCapacityReservation reservation =
                new BackendCapacityReservation(
                        UUID.randomUUID(),
                        playerId,
                        "lobby-1"
                );
        PlayerSessionLease lease = lease(playerId);

        BackendCapacityReserveRequest request =
                new BackendCapacityReserveRequest(
                        reservation,
                        lease
                );

        assertEquals(reservation, request.reservation());
        assertEquals(lease, request.sessionLease());
    }

    @Test
    void rejectsLeaseForDifferentPlayer() {
        BackendCapacityReservation reservation =
                new BackendCapacityReservation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "lobby-1"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendCapacityReserveRequest(
                        reservation,
                        lease(UUID.randomUUID())
                )
        );
    }

    private static PlayerSessionLease lease(UUID playerId) {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        playerId,
                        "HarriOcho",
                        1000L
                ),
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.randomUUID()
                ),
                9L
        );
    }
}

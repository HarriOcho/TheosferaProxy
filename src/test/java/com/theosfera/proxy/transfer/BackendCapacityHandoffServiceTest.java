package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendCapacityHandoffServiceTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID INCARNATION_ID = UUID.randomUUID();

    private BackendCapacityCoordinator coordinator;
    private BackendCapacityHandoffRegistry registry;
    private BackendCapacityHandoffService service;
    private PlayerSessionLease lease;
    private BackendCapacityReserveRequest request;

    @BeforeEach
    void setUp() {
        coordinator = mock(BackendCapacityCoordinator.class);
        registry = new BackendCapacityHandoffRegistry();
        service = new BackendCapacityHandoffService(
                coordinator,
                registry,
                mock(Logger.class)
        );
        lease = lease(7L);
        request = request(lease, "lobby-1");
    }

    @Test
    void confirmedPresenceReleasesExactReservationAndClosesHandoff() {
        when(coordinator.releaseIfOwned(request))
                .thenReturn(CompletableFuture.completedFuture(true));
        service.registerAfterConnectionSuccess(request);

        service.onPresenceConfirmed(lease, "lobby-1");

        verify(coordinator).releaseIfOwned(request);
        assertTrue(service.pendingHandoffs() == 0);
    }

    @Test
    void mismatchedBackendOrLeaseCannotCloseHandoff() {
        service.registerAfterConnectionSuccess(request);

        service.onPresenceConfirmed(lease, "skyblock-1");
        service.onPresenceConfirmed(lease(8L), "lobby-1");

        verify(coordinator, never()).releaseIfOwned(request);
        assertTrue(service.pendingHandoffs() == 1);
    }

    @Test
    void releaseFailureAfterConfirmedPresenceReliesOnTtlWithoutLeakingLocalHandoff() {
        when(coordinator.releaseIfOwned(request))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("redis unavailable")
                ));
        service.registerAfterConnectionSuccess(request);

        service.onPresenceConfirmed(lease, "lobby-1");

        verify(coordinator).releaseIfOwned(request);
        assertTrue(service.pendingHandoffs() == 0);
    }

    @Test
    void disconnectReleaseRequiresExactLease() {
        when(coordinator.releaseIfOwned(request))
                .thenReturn(CompletableFuture.completedFuture(true));
        service.registerAfterConnectionSuccess(request);

        assertTrue(
                service.releaseForDisconnect(lease(6L))
                        .toCompletableFuture()
                        .join()
        );
        verify(coordinator, never()).releaseIfOwned(request);
        assertTrue(service.pendingHandoffs() == 1);

        assertTrue(
                service.releaseForDisconnect(lease)
                        .toCompletableFuture()
                        .join()
        );
        verify(coordinator).releaseIfOwned(request);
        assertTrue(service.pendingHandoffs() == 0);
    }

    @Test
    void synchronousDisconnectReleaseFailureStillDropsLocalHandoffForTtlRecovery() {
        when(coordinator.releaseIfOwned(request))
                .thenThrow(new RuntimeException("redis unavailable"));
        service.registerAfterConnectionSuccess(request);

        assertFalse(
                service.releaseForDisconnect(lease)
                        .toCompletableFuture()
                        .join()
        );
        assertTrue(service.pendingHandoffs() == 0);
    }

    private PlayerSessionLease lease(long fencingToken) {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
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
            PlayerSessionLease sessionLease,
            String backendName
    ) {
        return new BackendCapacityReserveRequest(
                new BackendCapacityReservation(
                        UUID.randomUUID(),
                        PLAYER_ID,
                        backendName
                ),
                sessionLease
        );
    }
}

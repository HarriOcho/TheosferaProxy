package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributedBackendCapacityReleaseServiceTest {

    @Test
    void onlyConfirmedExactReleaseReturnsTrue() {
        BackendCapacityCoordinator coordinator =
                mock(BackendCapacityCoordinator.class);
        BackendCapacityReserveRequest request = request();
        when(coordinator.releaseIfOwned(request))
                .thenReturn(CompletableFuture.completedFuture(true));

        DistributedBackendCapacityReleaseService service =
                new DistributedBackendCapacityReleaseService(
                        coordinator,
                        mock(Logger.class)
                );

        assertTrue(
                service.releaseIfOwned(request)
                        .toCompletableFuture()
                        .join()
        );
    }

    @Test
    void falseExceptionalSynchronousAndNullReleasesFailClosed() {
        BackendCapacityReserveRequest request = request();

        BackendCapacityCoordinator falseCoordinator =
                mock(BackendCapacityCoordinator.class);
        when(falseCoordinator.releaseIfOwned(request))
                .thenReturn(CompletableFuture.completedFuture(false));
        assertFalse(service(falseCoordinator)
                .releaseIfOwned(request).toCompletableFuture().join());

        BackendCapacityCoordinator exceptionalCoordinator =
                mock(BackendCapacityCoordinator.class);
        when(exceptionalCoordinator.releaseIfOwned(request))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("redis unavailable")
                ));
        assertFalse(service(exceptionalCoordinator)
                .releaseIfOwned(request).toCompletableFuture().join());

        BackendCapacityCoordinator throwingCoordinator =
                mock(BackendCapacityCoordinator.class);
        when(throwingCoordinator.releaseIfOwned(request))
                .thenThrow(new IllegalStateException("internal"));
        assertFalse(service(throwingCoordinator)
                .releaseIfOwned(request).toCompletableFuture().join());

        BackendCapacityCoordinator nullCoordinator =
                mock(BackendCapacityCoordinator.class);
        when(nullCoordinator.releaseIfOwned(request)).thenReturn(null);
        assertFalse(service(nullCoordinator)
                .releaseIfOwned(request).toCompletableFuture().join());
    }

    private DistributedBackendCapacityReleaseService service(
            BackendCapacityCoordinator coordinator
    ) {
        return new DistributedBackendCapacityReleaseService(
                coordinator,
                mock(Logger.class)
        );
    }

    private BackendCapacityReserveRequest request() {
        UUID playerId = UUID.fromString(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        );
        return new BackendCapacityReserveRequest(
                new BackendCapacityReservation(
                        UUID.fromString(
                                "11111111-2222-3333-4444-555555555555"
                        ),
                        playerId,
                        "skyblock-1"
                ),
                new PlayerSessionLease(
                        new AuthenticatedPlayerSession(
                                playerId,
                                "HarriOcho",
                                1_000L
                        ),
                        new ProxyInstanceIdentity(
                                "proxy-1",
                                UUID.fromString(
                                        "99999999-8888-7777-6666-555555555555"
                                )
                        ),
                        7L
                )
        );
    }
}

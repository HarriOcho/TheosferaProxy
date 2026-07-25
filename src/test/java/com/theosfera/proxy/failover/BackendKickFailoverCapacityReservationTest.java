package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.BackendCapacityReservation;
import com.theosfera.proxy.transfer.BackendCapacityReservationResult;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendKickFailoverCapacityReservationTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "5b8578de-76a6-467f-9211-399b3dfe723a"
            );

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private BackendIdentityRegistry identityRegistry;
    private TransferTargetResolver targetResolver;
    private BackendBootstrapRegistry bootstrapRegistry;
    private PendingPlayerFailoverRegistry failoverRegistry;
    private BackendKickFailoverService service;
    private Player player;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();
        identityRegistry =
                new BackendIdentityRegistry();
        targetResolver =
                mock(TransferTargetResolver.class);
        bootstrapRegistry =
                new BackendBootstrapRegistry();
        failoverRegistry =
                new PendingPlayerFailoverRegistry();
        player = mock(Player.class);

        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_750_000_000_000L
                )
        );

        identityRegistry.register(
                new BackendIdentity(
                        "skyblock-1",
                        BackendType.SKYBLOCK
                )
        );

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getCurrentServer()).thenReturn(Optional.empty());

        when(targetResolver.reserveCapacity(
                any(BackendCapacityReservation.class),
                any(RegisteredServer.class)
        )).thenReturn(
                BackendCapacityReservationResult.RESERVED
        );

        service =
                new BackendKickFailoverService(
                        sessionRegistry,
                        identityRegistry,
                        targetResolver,
                        bootstrapRegistry,
                        failoverRegistry
                );
    }

    @Test
    void reservesAndReleasesCapacityOnSuccessfulConnection() {
        RegisteredServer target =
                server("skyblock-2");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(target)
        );

        BackendKickFailoverResolution resolution =
                service.resolveFailoverTarget(
                        event(server("skyblock-1"))
                );

        assertSame(
                BackendKickFailoverResolutionStatus.REDIRECT,
                resolution.status()
        );

        ArgumentCaptor<BackendCapacityReservation> captor =
                ArgumentCaptor.forClass(
                        BackendCapacityReservation.class
                );

        verify(targetResolver).reserveCapacity(
                captor.capture(),
                same(target)
        );

        BackendCapacityReservation reservation =
                captor.getValue();

        assertEquals(PLAYER_ID, reservation.playerId());
        assertEquals("skyblock-2", reservation.backendName());

        service.clearPendingFailover(PLAYER_ID);

        verify(targetResolver).releaseCapacity(reservation);
        assertFalse(failoverRegistry.isReserved(PLAYER_ID));
    }

    @Test
    void releasesCapacityWhenPlayerDisconnects() {
        RegisteredServer target =
                server("skyblock-2");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(target)
        );

        service.resolveFailoverTarget(
                event(server("skyblock-1"))
        );

        ArgumentCaptor<BackendCapacityReservation> captor =
                ArgumentCaptor.forClass(
                        BackendCapacityReservation.class
                );

        verify(targetResolver).reserveCapacity(
                captor.capture(),
                same(target)
        );

        service.cancelPendingFailover(PLAYER_ID);

        verify(targetResolver).releaseCapacity(
                captor.getValue()
        );

        assertFalse(failoverRegistry.isReserved(PLAYER_ID));
    }

    @Test
    void retriesAnotherBackendWhenCapacityRaceIsLost() {
        RegisteredServer first =
                server("skyblock-2");
        RegisteredServer second =
                server("skyblock-3");

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1")
        )).thenReturn(
                TransferTargetResolution.resolved(first)
        );

        when(targetResolver.resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1", "skyblock-2")
        )).thenReturn(
                TransferTargetResolution.resolved(second)
        );

        when(targetResolver.reserveCapacity(
                any(BackendCapacityReservation.class),
                same(first)
        )).thenReturn(
                BackendCapacityReservationResult.NO_CAPACITY
        );

        when(targetResolver.reserveCapacity(
                any(BackendCapacityReservation.class),
                same(second)
        )).thenReturn(
                BackendCapacityReservationResult.RESERVED
        );

        BackendKickFailoverResolution resolution =
                service.resolveFailoverTarget(
                        event(server("skyblock-1"))
                );

        assertSame(
                BackendKickFailoverResolutionStatus.REDIRECT,
                resolution.status()
        );

        assertSame(
                second,
                resolution.redirectTarget().orElseThrow()
        );

        verify(targetResolver).resolve(
                BackendType.SKYBLOCK,
                Set.of("skyblock-1", "skyblock-2")
        );
    }

    private KickedFromServerEvent event(
            RegisteredServer failedServer
    ) {
        KickedFromServerEvent event =
                mock(KickedFromServerEvent.class);

        when(event.getPlayer()).thenReturn(player);
        when(event.getServer()).thenReturn(failedServer);
        when(event.kickedDuringServerConnect())
                .thenReturn(false);
        when(event.getServerKickReason())
                .thenReturn(Optional.empty());

        return event;
    }

    private RegisteredServer server(String name) {
        RegisteredServer server =
                mock(RegisteredServer.class);
        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(server.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(name);

        return server;
    }
}

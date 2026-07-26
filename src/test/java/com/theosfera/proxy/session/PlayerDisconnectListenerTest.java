package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.local.LocalPlayerSessionCoordinator;
import com.theosfera.proxy.transfer.BackendCapacityReservationRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerDisconnectListenerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final UUID ACQUISITION_ID =
            UUID.fromString(
                    "22222222-3333-4444-5555-666666666666"
            );

    private static final ProxyInstanceIdentity PROXY_IDENTITY =
            new ProxyInstanceIdentity(
                    "proxy-disconnect-test",
                    UUID.fromString(
                            "09989199-f70d-4a0a-b442-0efd5aed14ef"
                    )
            );

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerSessionCoordinator sessionCoordinator;
    private PlayerSessionLeaseBindingRegistry leaseBindingRegistry;
    private PlayerServerPresenceRegistry presenceRegistry;
    private PendingPlayerTransferRegistry transferRegistry;
    private Logger logger;
    private Player player;
    private PlayerDisconnectListener listener;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        sessionCoordinator =
                new LocalPlayerSessionCoordinator(
                        sessionRegistry
                );

        leaseBindingRegistry =
                new PlayerSessionLeaseBindingRegistry();

        presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );

        transferRegistry =
                new PendingPlayerTransferRegistry();

        logger = mock(Logger.class);
        player = player(PLAYER_ID);

        listener = new PlayerDisconnectListener(
                sessionCoordinator,
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                logger
        );
    }

    @Test
    void removesLeaseSessionPresenceAndTransfer() {
        PlayerSessionLease lease =
                registerSessionLease(player);

        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "lobby-1",
                        2_000L
                )
        );

        transferRegistry.register(
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1",
                        "skyblock-1",
                        3_000L
                )
        );

        listener.onDisconnect(
                disconnectEvent(player)
        );

        assertFalse(
                sessionRegistry.find(PLAYER_ID).isPresent()
        );

        assertFalse(
                presenceRegistry.find(PLAYER_ID).isPresent()
        );

        assertFalse(
                transferRegistry
                        .findByPlayer(PLAYER_ID)
                        .isPresent()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(player)
                        .isEmpty()
        );

        assertEquals(
                PlayerSessionAcquireResult.Status.ACQUIRED,
                PlayerSessionAcquireResult
                        .acquired(lease)
                        .status()
        );

        verify(logger).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void removesSessionLeaseWhenPresenceDoesNotExist() {
        registerSessionLease(player);

        listener.onDisconnect(
                disconnectEvent(player)
        );

        assertFalse(
                sessionRegistry.find(PLAYER_ID).isPresent()
        );

        assertTrue(
                leaseBindingRegistry
                        .find(player)
                        .isEmpty()
        );

        verify(logger).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void doesNotLogRemovalWithoutRegisteredState() {
        listener.onDisconnect(
                disconnectEvent(player)
        );

        verify(
                logger,
                never()
        ).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void pendingAcquisitionIsMarkedDisconnectedWithoutRelease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        PlayerDisconnectListener asyncListener =
                listenerWith(coordinator);

        leaseBindingRegistry.begin(
                player,
                ACQUISITION_ID
        );

        asyncListener.onDisconnect(
                disconnectEvent(player)
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(
                any(PlayerSessionLease.class)
        );

        verify(
                logger,
                never()
        ).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void oldDisconnectDoesNotReleaseNewConnectionLease() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        Player oldConnection = player(PLAYER_ID);
        Player newConnection = player(PLAYER_ID);

        PlayerSessionLease newLease =
                lease(2L);

        leaseBindingRegistry.begin(
                newConnection,
                ACQUISITION_ID
        );

        leaseBindingRegistry.bind(
                newConnection,
                ACQUISITION_ID,
                newLease
        );

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(oldConnection)
        );

        assertEquals(
                newLease,
                leaseBindingRegistry
                        .find(newConnection)
                        .orElseThrow()
        );

        verify(
                coordinator,
                never()
        ).releaseIfOwned(
                any(PlayerSessionLease.class)
        );
    }

    @Test
    void logsAfterAsynchronousLeaseReleaseCompletes() {
        PlayerSessionCoordinator coordinator =
                mock(PlayerSessionCoordinator.class);

        CompletableFuture<Boolean> releaseFuture =
                new CompletableFuture<>();

        PlayerSessionLease lease = lease(1L);

        leaseBindingRegistry.begin(
                player,
                ACQUISITION_ID
        );

        leaseBindingRegistry.bind(
                player,
                ACQUISITION_ID,
                lease
        );

        when(coordinator.releaseIfOwned(lease))
                .thenReturn(releaseFuture);

        listenerWith(coordinator).onDisconnect(
                disconnectEvent(player)
        );

        verify(coordinator).releaseIfOwned(lease);


        verify(
                logger,
                never()
        ).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );

        releaseFuture.complete(true);


        verify(logger).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void rejectsNullEvent() {
        assertThrows(
                NullPointerException.class,
                () -> listener.onDisconnect(null)
        );
    }

    @Test
    void rejectsNullConstructorArguments() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        null,
                        leaseBindingRegistry,
                        presenceRegistry,
                        transferRegistry,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        sessionCoordinator,
                        null,
                        presenceRegistry,
                        transferRegistry,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        null,
                        transferRegistry,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        presenceRegistry,
                        null,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        presenceRegistry,
                        transferRegistry,
                        null
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        sessionCoordinator,
                        leaseBindingRegistry,
                        presenceRegistry,
                        transferRegistry,
                        null,
                        logger
                )
        );
    }

    private PlayerSessionLease registerSessionLease(
            Player exactPlayer
    ) {
        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_000L
                );

        PlayerSessionAcquireResult result =
                sessionCoordinator.acquire(
                        new PlayerSessionLeaseRequest(
                                session,
                                PROXY_IDENTITY
                        )
                ).toCompletableFuture().join();

        PlayerSessionLease lease =
                result.lease().orElseThrow();

        leaseBindingRegistry.begin(
                exactPlayer,
                ACQUISITION_ID
        );

        leaseBindingRegistry.bind(
                exactPlayer,
                ACQUISITION_ID,
                lease
        );

        return lease;
    }

    private PlayerSessionLease lease(long fencingToken) {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_000L
                ),
                PROXY_IDENTITY,
                fencingToken
        );
    }

    private PlayerDisconnectListener listenerWith(
            PlayerSessionCoordinator coordinator
    ) {
        return new PlayerDisconnectListener(
                coordinator,
                leaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                logger
        );
    }

    private Player player(UUID playerId) {
        Player mockedPlayer = mock(Player.class);

        when(mockedPlayer.getUniqueId())
                .thenReturn(playerId);

        when(mockedPlayer.getUsername())
                .thenReturn("HarriOcho");

        return mockedPlayer;
    }

    private DisconnectEvent disconnectEvent(
            Player exactPlayer
    ) {
        DisconnectEvent event =
                mock(DisconnectEvent.class);

        when(event.getPlayer())
                .thenReturn(exactPlayer);

        return event;
    }
}

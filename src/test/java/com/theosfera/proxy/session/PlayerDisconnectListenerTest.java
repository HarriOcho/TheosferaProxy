package com.theosfera.proxy.session;

import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerServerPresenceRegistry presenceRegistry;
    private PendingPlayerTransferRegistry transferRegistry;
    private Logger logger;
    private PlayerDisconnectListener listener;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();
        presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );
        transferRegistry =
                new PendingPlayerTransferRegistry();
        logger = mock(Logger.class);
        listener = new PlayerDisconnectListener(
                sessionRegistry,
                presenceRegistry,
                transferRegistry,
                logger
        );
    }

    @Test
    void removesSessionAndPresenceWhenPlayerDisconnects() {
        registerPlayerState();
        transferRegistry.register(
                new PendingPlayerTransfer(
                        REQUEST_ID,
                        PLAYER_ID,
                        "lobby-1",
                        "skyblock-1",
                        3_000L
                )
        );

        listener.onDisconnect(disconnectEvent());

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

        verify(logger).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void removesSessionWhenPresenceDoesNotExist() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_000L
                )
        );

        listener.onDisconnect(disconnectEvent());

        assertFalse(
                sessionRegistry.find(PLAYER_ID).isPresent()
        );

        verify(logger).debug(
                "Estado de sesión eliminado para {} "
                        + "al desconectarse del proxy.",
                PLAYER_ID
        );
    }

    @Test
    void doesNotLogRemovalWhenPlayerHasNoRegisteredState() {
        listener.onDisconnect(disconnectEvent());

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
                        presenceRegistry,
                        transferRegistry,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        sessionRegistry,
                        null,
                        transferRegistry,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        sessionRegistry,
                        presenceRegistry,
                        null,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerDisconnectListener(
                        sessionRegistry,
                        presenceRegistry,
                        transferRegistry,
                        null
                )
        );
    }

    private void registerPlayerState() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        1_000L
                )
        );

        presenceRegistry.update(
                new PlayerServerPresence(
                        PLAYER_ID,
                        "lobby-1",
                        2_000L
                )
        );
    }

    private DisconnectEvent disconnectEvent() {
        Player player = mock(Player.class);
        DisconnectEvent event =
                mock(DisconnectEvent.class);

        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        return event;
    }
}
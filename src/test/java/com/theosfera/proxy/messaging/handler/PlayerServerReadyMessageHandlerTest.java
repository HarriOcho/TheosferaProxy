package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PlayerServerReadyPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerServerReadyMessageHandlerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final long AUTHENTICATED_AT = 1_000L;
    private static final long READY_AT = 2_000L;

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerServerPresenceRegistry presenceRegistry;
    private Logger logger;
    private PlayerServerReadyMessageHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();
        presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );
        logger = mock(Logger.class);
        handler = new PlayerServerReadyMessageHandler(
                presenceRegistry,
                logger
        );
    }

    @Test
    void declaresPlayerServerReadyMessageType() {
        assertEquals(
                ProtocolMessageType.PLAYER_SERVER_READY,
                handler.messageType()
        );
    }

    @Test
    void recordsPresenceForAuthenticatedPlayer() {
        authenticatePlayer();

        handler.handle(
                readyContext(
                        "lobby-1",
                        "lobby-1",
                        READY_AT
                )
        );

        PlayerServerPresence presence =
                presenceRegistry.find(PLAYER_ID).orElseThrow();

        assertEquals(PLAYER_ID, presence.playerId());
        assertEquals("lobby-1", presence.backendName());
        assertEquals(READY_AT, presence.readyAt());

        verify(logger).info(
                "Jugador {} listo en {}.",
                PLAYER_ID,
                "lobby-1"
        );
    }

    @Test
    void updatesPresenceWithNewerReadyEvent() {
        authenticatePlayer();

        handler.handle(
                readyContext(
                        "lobby-1",
                        "lobby-1",
                        READY_AT
                )
        );

        handler.handle(
                readyContext(
                        "skyblock-1",
                        "skyblock-1",
                        READY_AT + 1
                )
        );

        PlayerServerPresence presence =
                presenceRegistry.find(PLAYER_ID).orElseThrow();

        assertEquals("skyblock-1", presence.backendName());
        assertEquals(READY_AT + 1, presence.readyAt());

        verify(logger).info(
                "Jugador {} listo en {}.",
                PLAYER_ID,
                "skyblock-1"
        );
    }

    @Test
    void treatsRepeatedPresenceAsAlreadyRecorded() {
        authenticatePlayer();

        ProtocolMessageContext context =
                readyContext(
                        "lobby-1",
                        "lobby-1",
                        READY_AT
                );

        handler.handle(context);
        handler.handle(context);

        assertEquals(1, presenceRegistry.snapshot().size());

        verify(logger).debug(
                "Presencia ya registrada para {} en {}.",
                PLAYER_ID,
                "lobby-1"
        );
    }

    @Test
    void rejectsPresenceForUnauthenticatedPlayer() {
        handler.handle(
                readyContext(
                        "lobby-1",
                        "lobby-1",
                        READY_AT
                )
        );

        assertFalse(
                presenceRegistry.find(PLAYER_ID).isPresent()
        );

        verify(logger).warn(
                "PLAYER_SERVER_READY rechazado para {}: "
                        + "el jugador no está autenticado.",
                PLAYER_ID
        );
    }

    @Test
    void ignoresStaleReadyEvent() {
        authenticatePlayer();

        handler.handle(
                readyContext(
                        "skyblock-1",
                        "skyblock-1",
                        READY_AT + 1
                )
        );

        handler.handle(
                readyContext(
                        "lobby-1",
                        "lobby-1",
                        READY_AT
                )
        );

        PlayerServerPresence presence =
                presenceRegistry.find(PLAYER_ID).orElseThrow();

        assertEquals("skyblock-1", presence.backendName());
        assertEquals(READY_AT + 1, presence.readyAt());

        verify(logger).debug(
                "PLAYER_SERVER_READY atrasado ignorado "
                        + "para {} desde {}.",
                PLAYER_ID,
                "lobby-1"
        );
    }

    @Test
    void preservesPresenceWhenEqualTimestampConflicts() {
        authenticatePlayer();

        handler.handle(
                readyContext(
                        "lobby-1",
                        "lobby-1",
                        READY_AT
                )
        );

        handler.handle(
                readyContext(
                        "skyblock-1",
                        "skyblock-1",
                        READY_AT
                )
        );

        PlayerServerPresence presence =
                presenceRegistry.find(PLAYER_ID).orElseThrow();

        assertEquals("lobby-1", presence.backendName());
        assertEquals(READY_AT, presence.readyAt());

        verify(logger).warn(
                "Conflicto de presencia para {} desde {}.",
                PLAYER_ID,
                "skyblock-1"
        );
    }

    @Test
    void rejectsBackendNameThatDoesNotMatchSource() {
        authenticatePlayer();

        handler.handle(
                readyContext(
                        "lobby-1",
                        "skyblock-1",
                        READY_AT
                )
        );

        assertFalse(
                presenceRegistry.find(PLAYER_ID).isPresent()
        );

        verify(logger).warn(
                "PLAYER_SERVER_READY rechazado desde {}: "
                        + "el payload declaró otro backend.",
                "lobby-1"
        );

        verify(
                logger,
                never()
        ).info(
                "Jugador {} listo en {}.",
                PLAYER_ID,
                "skyblock-1"
        );
    }

    @Test
    void rejectsEnvelopeWithUnexpectedPayload() {
        ProtocolEnvelope<PingPayload> envelope =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PLAYER_SERVER_READY,
                        new PingPayload(READY_AT)
                );

        ProtocolMessageContext context = createContext(
                "lobby-1",
                envelope
        );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> handler.handle(context)
                );

        assertTrue(
                exception.getMessage().contains(
                        "PlayerServerReadyPayload"
                )
        );
    }

    @Test
    void rejectsNullContext() {
        assertThrows(
                NullPointerException.class,
                () -> handler.handle(null)
        );
    }

    @Test
    void rejectsNullConstructorArguments() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerServerReadyMessageHandler(
                        null,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerServerReadyMessageHandler(
                        presenceRegistry,
                        null
                )
        );
    }

    private void authenticatePlayer() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                )
        );
    }

    private ProtocolMessageContext readyContext(
            String sourceServer,
            String declaredBackend,
            long readyAt
    ) {
        ProtocolEnvelope<PlayerServerReadyPayload> envelope =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PLAYER_SERVER_READY,
                        new PlayerServerReadyPayload(
                                PLAYER_ID,
                                declaredBackend,
                                readyAt
                        )
                );

        return createContext(
                sourceServer,
                envelope
        );
    }

    private ProtocolMessageContext createContext(
            String serverName,
            ProtocolEnvelope<?> envelope
    ) {
        ServerConnection source =
                mock(ServerConnection.class);
        ServerInfo serverInfo =
                mock(ServerInfo.class);

        when(source.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(serverName);

        return new ProtocolMessageContext(
                source,
                envelope
        );
    }
}
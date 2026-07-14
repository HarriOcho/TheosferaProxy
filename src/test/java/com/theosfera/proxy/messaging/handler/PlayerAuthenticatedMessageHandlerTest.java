package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerAuthenticationAckSender;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerAuthenticatedMessageHandlerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final UUID OTHER_PLAYER_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
            );

    private static final long AUTHENTICATED_AT = 1_000L;

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private PlayerAuthenticationAckSender acknowledgementSender;
    private Logger logger;
    private PlayerAuthenticatedMessageHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        acknowledgementSender =
                mock(PlayerAuthenticationAckSender.class);

        logger = mock(Logger.class);

        handler = new PlayerAuthenticatedMessageHandler(
                sessionRegistry,
                acknowledgementSender,
                logger
        );
    }

    @Test
    void declaresPlayerAuthenticatedMessageType() {
        assertEquals(
                ProtocolMessageType.PLAYER_AUTHENTICATED,
                handler.messageType()
        );
    }

    @Test
    void registersAuthenticatedPlayerSession() {
        ProtocolMessageContext context = createContext(
                "auth-1",
                PLAYER_ID,
                "HarriOcho",
                authenticatedEnvelope(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                )
        );

        handler.handle(context);

        AuthenticatedPlayerSession session =
                sessionRegistry
                        .find(PLAYER_ID)
                        .orElseThrow();

        assertEquals(PLAYER_ID, session.playerId());
        assertEquals(
                "HarriOcho",
                session.playerName()
        );

        assertEquals(
                AUTHENTICATED_AT,
                session.authenticatedAt()
        );

        verify(acknowledgementSender).send(
                context,
                PLAYER_ID,
                true,
                "Player session registered"
        );

        verify(logger).info(
                "Sesión autenticada registrada para {} "
                        + "({}) desde {}.",
                "HarriOcho",
                PLAYER_ID,
                "auth-1"
        );
    }

    @Test
    void treatsRepeatedSessionAsAlreadyRegistered() {
        ProtocolMessageContext context = createContext(
                "auth-1",
                PLAYER_ID,
                "HarriOcho",
                authenticatedEnvelope(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                )
        );

        handler.handle(context);
        handler.handle(context);

        assertEquals(
                1,
                sessionRegistry.snapshot().size()
        );

        verify(acknowledgementSender).send(
                context,
                PLAYER_ID,
                true,
                "Player session already registered"
        );

        verify(logger).debug(
                "Sesión autenticada ya registrada para {} "
                        + "({}).",
                "HarriOcho",
                PLAYER_ID
        );
    }

    @Test
    void preservesExistingSessionWhenRegistrationConflicts() {
        AuthenticatedPlayerSession existingSession =
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                );

        sessionRegistry.register(existingSession);

        ProtocolMessageContext context = createContext(
                "auth-1",
                PLAYER_ID,
                "HarriOcho",
                authenticatedEnvelope(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT + 1
                )
        );

        handler.handle(context);

        assertEquals(
                existingSession,
                sessionRegistry
                        .find(PLAYER_ID)
                        .orElseThrow()
        );

        verify(acknowledgementSender).send(
                context,
                PLAYER_ID,
                false,
                "Player session conflict"
        );

        verify(logger).warn(
                "Conflicto de sesión autenticada para {} "
                        + "recibido desde {}.",
                PLAYER_ID,
                "auth-1"
        );
    }

    @Test
    void rejectsPayloadForDifferentPlayerId() {
        ProtocolMessageContext context = createContext(
                "auth-1",
                OTHER_PLAYER_ID,
                "HarriOcho",
                authenticatedEnvelope(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                )
        );

        handler.handle(context);

        assertTrue(
                sessionRegistry
                        .find(PLAYER_ID)
                        .isEmpty()
        );

        verify(acknowledgementSender).send(
                context,
                PLAYER_ID,
                false,
                "Player identity mismatch"
        );

        verify(logger).warn(
                "PLAYER_AUTHENTICATED rechazado desde {}: "
                        + "la identidad declarada ({}, {}) "
                        + "no coincide con el jugador portador "
                        + "({}, {}).",
                "auth-1",
                PLAYER_ID,
                "HarriOcho",
                OTHER_PLAYER_ID,
                "HarriOcho"
        );
    }

    @Test
    void rejectsPayloadForDifferentPlayerName() {
        ProtocolMessageContext context = createContext(
                "auth-1",
                PLAYER_ID,
                "OtroNombre",
                authenticatedEnvelope(
                        PLAYER_ID,
                        "HarriOcho",
                        AUTHENTICATED_AT
                )
        );

        handler.handle(context);

        assertTrue(
                sessionRegistry
                        .find(PLAYER_ID)
                        .isEmpty()
        );

        verify(acknowledgementSender).send(
                context,
                PLAYER_ID,
                false,
                "Player identity mismatch"
        );

        verify(logger).warn(
                "PLAYER_AUTHENTICATED rechazado desde {}: "
                        + "la identidad declarada ({}, {}) "
                        + "no coincide con el jugador portador "
                        + "({}, {}).",
                "auth-1",
                PLAYER_ID,
                "HarriOcho",
                PLAYER_ID,
                "OtroNombre"
        );
    }

    @Test
    void rejectsEnvelopeWithUnexpectedPayload() {
        ProtocolEnvelope<PingPayload> envelope =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PLAYER_AUTHENTICATED,
                        new PingPayload(1_000L)
                );

        ProtocolMessageContext context = createContext(
                "auth-1",
                PLAYER_ID,
                "HarriOcho",
                envelope
        );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> handler.handle(context)
                );

        assertTrue(
                exception.getMessage().contains(
                        "PlayerAuthenticatedPayload"
                )
        );

        verify(
                acknowledgementSender,
                never()
        ).send(
                context,
                PLAYER_ID,
                true,
                "Player session registered"
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
                () -> new PlayerAuthenticatedMessageHandler(
                        null,
                        acknowledgementSender,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        sessionRegistry,
                        null,
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        sessionRegistry,
                        acknowledgementSender,
                        null
                )
        );
    }

    private ProtocolEnvelope<PlayerAuthenticatedPayload>
    authenticatedEnvelope(
            UUID playerId,
            String playerName,
            long authenticatedAt
    ) {
        return ProtocolEnvelope.create(
                ProtocolMessageType.PLAYER_AUTHENTICATED,
                new PlayerAuthenticatedPayload(
                        playerId,
                        playerName,
                        authenticatedAt
                )
        );
    }

    private ProtocolMessageContext createContext(
            String serverName,
            UUID carrierId,
            String carrierName,
            ProtocolEnvelope<?> envelope
    ) {
        ServerConnection source =
                mock(ServerConnection.class);

        ServerInfo serverInfo =
                mock(ServerInfo.class);

        Player carrier = mock(Player.class);

        when(source.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(serverName);
        when(source.getPlayer()).thenReturn(carrier);
        when(carrier.getUniqueId()).thenReturn(carrierId);
        when(carrier.getUsername()).thenReturn(carrierName);

        return new ProtocolMessageContext(
                source,
                envelope
        );
    }
}

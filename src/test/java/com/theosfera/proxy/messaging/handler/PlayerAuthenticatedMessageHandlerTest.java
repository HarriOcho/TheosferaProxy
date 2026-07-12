package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerAuthenticatedMessageHandlerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final long AUTHENTICATED_AT = 1_000L;

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private Logger logger;
    private PlayerAuthenticatedMessageHandler handler;

    @BeforeEach
    void setUp() {
        sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();
        logger = mock(Logger.class);
        handler = new PlayerAuthenticatedMessageHandler(
                sessionRegistry,
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
                authenticatedEnvelope(
                        "HarriOcho",
                        AUTHENTICATED_AT
                )
        );

        handler.handle(context);

        AuthenticatedPlayerSession session =
                sessionRegistry.find(PLAYER_ID).orElseThrow();

        assertEquals(PLAYER_ID, session.playerId());
        assertEquals("HarriOcho", session.playerName());
        assertEquals(
                AUTHENTICATED_AT,
                session.authenticatedAt()
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
                authenticatedEnvelope(
                        "HarriOcho",
                        AUTHENTICATED_AT
                )
        );

        handler.handle(context);
        handler.handle(context);

        assertEquals(1, sessionRegistry.snapshot().size());

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
                authenticatedEnvelope(
                        "OtroNombre",
                        AUTHENTICATED_AT + 1
                )
        );

        handler.handle(context);

        assertEquals(
                existingSession,
                sessionRegistry.find(PLAYER_ID).orElseThrow()
        );

        verify(logger).warn(
                "Conflicto de sesión autenticada para {} "
                        + "recibido desde {}.",
                PLAYER_ID,
                "auth-1"
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
                        logger
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PlayerAuthenticatedMessageHandler(
                        sessionRegistry,
                        null
                )
        );
    }

    private ProtocolEnvelope<PlayerAuthenticatedPayload>
    authenticatedEnvelope(
            String playerName,
            long authenticatedAt
    ) {
        return ProtocolEnvelope.create(
                ProtocolMessageType.PLAYER_AUTHENTICATED,
                new PlayerAuthenticatedPayload(
                        PLAYER_ID,
                        playerName,
                        authenticatedAt
                )
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
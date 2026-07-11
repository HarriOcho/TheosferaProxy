package com.theosfera.proxy.messaging;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.velocitypowered.api.proxy.ServerConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolMessageDispatcherTest {

    private static final long SENT_AT =
            1_750_000_000_000L;

    @Test
    void dispatchesMessageToMatchingHandler() {
        ProtocolMessageHandler handler =
                createHandler(ProtocolMessageType.PING);

        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(handler)
                );

        ProtocolMessageContext context =
                createContext(ProtocolMessageType.PING);

        assertTrue(dispatcher.dispatch(context));
        verify(handler).handle(context);
    }

    @Test
    void returnsFalseWhenNoHandlerIsRegistered() {
        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(List.of());

        assertFalse(
                dispatcher.dispatch(
                        createContext(
                                ProtocolMessageType.PING
                        )
                )
        );
    }

    @Test
    void rejectsDuplicateHandlers() {
        ProtocolMessageHandler first =
                createHandler(ProtocolMessageType.PING);
        ProtocolMessageHandler second =
                createHandler(ProtocolMessageType.PING);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProtocolMessageDispatcher(
                        List.of(first, second)
                )
        );
    }

    @Test
    void rejectsHandlerWithUnknownType() {
        ProtocolMessageHandler handler =
                createHandler("UNKNOWN_MESSAGE");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProtocolMessageDispatcher(
                        List.of(handler)
                )
        );
    }

    @Test
    void rejectsNullHandlerCollection() {
        assertThrows(
                NullPointerException.class,
                () -> new ProtocolMessageDispatcher(null)
        );
    }

    @Test
    void rejectsNullHandler() {
        assertThrows(
                NullPointerException.class,
                () -> new ProtocolMessageDispatcher(
                        java.util.Arrays.asList(
                                (ProtocolMessageHandler) null
                        )
                )
        );
    }

    @Test
    void rejectsHandlerWithNullType() {
        ProtocolMessageHandler handler =
                mock(ProtocolMessageHandler.class);

        assertThrows(
                NullPointerException.class,
                () -> new ProtocolMessageDispatcher(
                        List.of(handler)
                )
        );
    }

    @Test
    void rejectsNullContext() {
        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(List.of());

        assertThrows(
                NullPointerException.class,
                () -> dispatcher.dispatch(null)
        );
    }

    @Test
    void exposesImmutableHandlerTypes() {
        ProtocolMessageHandler handler =
                createHandler(ProtocolMessageType.PING);

        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(handler)
                );

        Set<String> handlerTypes =
                dispatcher.registeredHandlerTypes();

        assertThrows(
                UnsupportedOperationException.class,
                () -> handlerTypes.add(
                        ProtocolMessageType.PONG
                )
        );
    }

    private ProtocolMessageHandler createHandler(
            String messageType
    ) {
        ProtocolMessageHandler handler =
                mock(ProtocolMessageHandler.class);

        when(handler.messageType()).thenReturn(messageType);
        return handler;
    }

    private ProtocolMessageContext createContext(
            String messageType
    ) {
        ProtocolEnvelope<PingPayload> envelope =
                ProtocolEnvelope.create(
                        messageType,
                        new PingPayload(SENT_AT)
                );

        return new ProtocolMessageContext(
                mock(ServerConnection.class),
                envelope
        );
    }
}
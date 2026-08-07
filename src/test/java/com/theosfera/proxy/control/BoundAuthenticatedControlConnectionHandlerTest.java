package com.theosfera.proxy.control;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.transport.ProtocolFrameCodec;
import com.theosfera.proxy.backend.BackendIdentity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundAuthenticatedControlConnectionHandlerTest {

    @Test
    void bindsOutputOnlyWhileDelegateOwnsCurrentSession()
            throws Exception {
        BackendControlSessionRegistry sessionRegistry =
                new BackendControlSessionRegistry();
        BackendControlSession session = register(
                sessionRegistry,
                "lobby-1"
        );
        BackendControlMessageSender sender =
                new BackendControlMessageSender(
                        new ProtocolJsonCodec(),
                        new ProtocolFrameCodec(),
                        sessionRegistry
                );
        AtomicBoolean delegateCalled = new AtomicBoolean();
        AuthenticatedControlConnectionHandler delegate =
                (receivedSession, input, output) -> {
                    delegateCalled.set(true);
                    assertEquals(
                            session,
                            sender.findSession("lobby-1").orElseThrow()
                    );
                    assertEquals(1, sender.boundSessionCount());
                };

        BoundAuthenticatedControlConnectionHandler handler =
                new BoundAuthenticatedControlConnectionHandler(
                        sender,
                        delegate
                );

        handler.handle(
                session,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream()
        );

        assertTrue(delegateCalled.get());
        assertEquals(0, sender.boundSessionCount());
    }

    @Test
    void unbindsOutputWhenDelegateFails() {
        BackendControlSessionRegistry sessionRegistry =
                new BackendControlSessionRegistry();
        BackendControlSession session = register(
                sessionRegistry,
                "lobby-1"
        );
        BackendControlMessageSender sender =
                new BackendControlMessageSender(
                        new ProtocolJsonCodec(),
                        new ProtocolFrameCodec(),
                        sessionRegistry
                );
        AuthenticatedControlConnectionHandler delegate =
                (receivedSession, input, output) -> {
                    throw new IOException("boom");
                };

        BoundAuthenticatedControlConnectionHandler handler =
                new BoundAuthenticatedControlConnectionHandler(
                        sender,
                        delegate
                );

        IOException exception = assertThrows(
                IOException.class,
                () -> handler.handle(
                        session,
                        new ByteArrayInputStream(new byte[0]),
                        new ByteArrayOutputStream()
                )
        );

        assertEquals("boom", exception.getMessage());
        assertEquals(0, sender.boundSessionCount());
    }

    @Test
    void rejectsSessionThatWasAlreadyReplaced() {
        BackendControlSessionRegistry sessionRegistry =
                new BackendControlSessionRegistry();
        BackendControlSession stale = register(
                sessionRegistry,
                "lobby-1"
        );
        register(sessionRegistry, "lobby-1");
        BackendControlMessageSender sender =
                new BackendControlMessageSender(
                        new ProtocolJsonCodec(),
                        new ProtocolFrameCodec(),
                        sessionRegistry
                );
        AtomicBoolean delegateCalled = new AtomicBoolean();

        BoundAuthenticatedControlConnectionHandler handler =
                new BoundAuthenticatedControlConnectionHandler(
                        sender,
                        (receivedSession, input, output) ->
                                delegateCalled.set(true)
                );

        assertThrows(
                ControlConnectionProtocolException.class,
                () -> handler.handle(
                        stale,
                        new ByteArrayInputStream(new byte[0]),
                        new ByteArrayOutputStream()
                )
        );

        assertTrue(!delegateCalled.get());
        assertEquals(0, sender.boundSessionCount());
    }

    private static BackendControlSession register(
            BackendControlSessionRegistry registry,
            String backendName
    ) {
        return registry.register(
                UUID.randomUUID(),
                new BackendIdentity(
                        backendName,
                        BackendType.LOBBY
                )
        ).current();
    }
}

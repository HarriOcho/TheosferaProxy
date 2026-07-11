package com.theosfera.proxy.messaging;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PingPayload;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProtocolMessageContextTest {

    private static final ProtocolEnvelope<PingPayload> ENVELOPE =
            ProtocolEnvelope.create(
                    ProtocolMessageType.PING,
                    new PingPayload(1_750_000_000_000L)
            );

    @Test
    void exposesSourceEnvelopeAndServerName() {
        ServerConnection source =
                mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn("lobby-1");
        when(source.getServerInfo()).thenReturn(serverInfo);

        ProtocolMessageContext context =
                new ProtocolMessageContext(
                        source,
                        ENVELOPE
                );

        assertSame(source, context.source());
        assertSame(ENVELOPE, context.envelope());
        assertEquals("lobby-1", context.serverName());
    }

    @Test
    void rejectsNullSource() {
        assertThrows(
                NullPointerException.class,
                () -> new ProtocolMessageContext(
                        null,
                        ENVELOPE
                )
        );
    }

    @Test
    void rejectsNullEnvelope() {
        assertThrows(
                NullPointerException.class,
                () -> new ProtocolMessageContext(
                        mock(ServerConnection.class),
                        null
                )
        );
    }
}
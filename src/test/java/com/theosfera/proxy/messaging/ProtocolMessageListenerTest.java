package com.theosfera.proxy.messaging;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProtocolMessageListenerTest {

    private final Logger logger = mock(Logger.class);
    private final ChannelMessageSink sink =
            mock(ChannelMessageSink.class);

    private final ProtocolMessageListener listener =
            new ProtocolMessageListener(logger);

    @Test
    void ignoresMessagesFromOtherChannels() {
        Player player = mock(Player.class);

        PluginMessageEvent event = new PluginMessageEvent(
                player,
                sink,
                MinecraftChannelIdentifier.create(
                        "example",
                        "other"
                ),
                new byte[]{1}
        );

        listener.onPluginMessage(event);

        assertTrue(event.getResult().isAllowed());
        verifyNoInteractions(logger);
    }

    @Test
    void handlesAndRejectsProtocolMessagesFromPlayers() {
        Player player = mock(Player.class);

        PluginMessageEvent event = new PluginMessageEvent(
                player,
                sink,
                ProtocolChannel.IDENTIFIER,
                new byte[]{1}
        );

        listener.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());
        verify(logger).warn(
                "Mensaje de protocolo rechazado: "
                        + "el origen no es una conexión backend."
        );
    }

    @Test
    void handlesAndRejectsEmptyBackendMessages() {
        ServerConnection serverConnection =
                createServerConnection("lobby-1");

        PluginMessageEvent event = new PluginMessageEvent(
                serverConnection,
                sink,
                ProtocolChannel.IDENTIFIER,
                new byte[0]
        );

        listener.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());
        verify(logger).warn(
                "Mensaje de protocolo vacío rechazado desde {}.",
                "lobby-1"
        );
    }

    @Test
    void handlesAndRejectsOversizedBackendMessages() {
        ServerConnection serverConnection =
                createServerConnection("skyblock-1");

        byte[] oversized = new byte[
                ProtocolJsonCodec.MAX_MESSAGE_BYTES + 1
                ];

        PluginMessageEvent event = new PluginMessageEvent(
                serverConnection,
                sink,
                ProtocolChannel.IDENTIFIER,
                oversized
        );

        listener.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());
        verify(logger).warn(
                "Mensaje de protocolo sobredimensionado "
                        + "rechazado desde {}: {} bytes.",
                "skyblock-1",
                oversized.length
        );
    }

    @Test
    void handlesValidSizedBackendMessagesWithoutForwarding() {
        ServerConnection serverConnection =
                createServerConnection("lobby-1");

        byte[] data = new byte[]{1, 2, 3};

        PluginMessageEvent event = new PluginMessageEvent(
                serverConnection,
                sink,
                ProtocolChannel.IDENTIFIER,
                data
        );

        listener.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());
        verify(logger).debug(
                "Mensaje de protocolo recibido desde {}: {} bytes.",
                "lobby-1",
                data.length
        );
    }

    private ServerConnection createServerConnection(
            String serverName
    ) {
        ServerConnection serverConnection =
                mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);
        when(serverConnection.getServerInfo())
                .thenReturn(serverInfo);

        return serverConnection;
    }
}
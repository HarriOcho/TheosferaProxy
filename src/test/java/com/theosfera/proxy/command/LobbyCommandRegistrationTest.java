package com.theosfera.proxy.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyCommandRegistrationTest {

    @Test
    void registersHubAndLobbyOnSameCommandMeta() {
        ProxyServer proxyServer =
                mock(ProxyServer.class);

        CommandManager commandManager =
                mock(CommandManager.class);

        CommandMeta.Builder builder =
                mock(CommandMeta.Builder.class);

        CommandMeta commandMeta =
                mock(CommandMeta.class);

        Object plugin =
                new Object();

        LobbyCommand command =
                mock(LobbyCommand.class);

        when(proxyServer.getCommandManager())
                .thenReturn(commandManager);

        when(commandManager.metaBuilder("hub"))
                .thenReturn(builder);

        when(builder.aliases("lobby"))
                .thenReturn(builder);

        when(builder.plugin(plugin))
                .thenReturn(builder);

        when(builder.build())
                .thenReturn(commandMeta);

        LobbyCommandRegistration registration =
                new LobbyCommandRegistration(
                        proxyServer,
                        plugin,
                        command
                );

        registration.register();

        verify(commandManager).metaBuilder("hub");
        verify(builder).aliases("lobby");
        verify(builder).plugin(plugin);
        verify(commandManager).register(
                commandMeta,
                command
        );

    }

    @Test
    void unregistersRegisteredCommandMeta() {
        ProxyServer proxyServer =
                mock(ProxyServer.class);

        CommandManager commandManager =
                mock(CommandManager.class);

        CommandMeta.Builder builder =
                mock(CommandMeta.Builder.class);

        CommandMeta commandMeta =
                mock(CommandMeta.class);

        when(proxyServer.getCommandManager())
                .thenReturn(commandManager);

        when(commandManager.metaBuilder("hub"))
                .thenReturn(builder);

        when(builder.aliases("lobby"))
                .thenReturn(builder);

        when(builder.plugin(any()))
                .thenReturn(builder);

        when(builder.build())
                .thenReturn(commandMeta);

        LobbyCommandRegistration registration =
                new LobbyCommandRegistration(
                        proxyServer,
                        new Object(),
                        mock(LobbyCommand.class)
                );

        registration.register();
        registration.unregister();

        verify(commandManager).unregister(commandMeta);
    }

    @Test
    void ignoresUnregisterBeforeRegister() {
        ProxyServer proxyServer =
                mock(ProxyServer.class);

        CommandManager commandManager =
                mock(CommandManager.class);

        when(proxyServer.getCommandManager())
                .thenReturn(commandManager);

        LobbyCommandRegistration registration =
                new LobbyCommandRegistration(
                        proxyServer,
                        new Object(),
                        mock(LobbyCommand.class)
                );

        registration.unregister();

        verify(commandManager, never())
                .unregister(any(CommandMeta.class));
    }
}

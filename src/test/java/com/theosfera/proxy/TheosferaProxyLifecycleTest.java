package com.theosfera.proxy;

import com.theosfera.proxy.command.LobbyCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TheosferaProxyLifecycleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void registersAndUnregistersLobbyCommandInLifecycle() {
        ProxyServer proxyServer =
                mock(ProxyServer.class);

        ChannelRegistrar channelRegistrar =
                mock(ChannelRegistrar.class);

        EventManager eventManager =
                mock(EventManager.class);

        CommandManager commandManager =
                mock(CommandManager.class);

        CommandMeta.Builder builder =
                mock(CommandMeta.Builder.class);

        CommandMeta commandMeta =
                mock(CommandMeta.class);

        when(proxyServer.getChannelRegistrar())
                .thenReturn(channelRegistrar);

        when(proxyServer.getEventManager())
                .thenReturn(eventManager);

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

        TheosferaProxy plugin =
                new TheosferaProxy(
                        proxyServer,
                        mock(Logger.class),
                        temporaryDirectory
                );

        plugin.onProxyInitialization(null);

        verify(commandManager).register(
                eq(commandMeta),
                any(LobbyCommand.class)
        );

        plugin.onProxyShutdown(null);

        verify(commandManager).unregister(commandMeta);
    }
}

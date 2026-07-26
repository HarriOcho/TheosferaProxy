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

class ProxyStatusCommandRegistrationTest {

    @Test
    void registersAdministrativeProxyCommand() {
        ProxyServer proxyServer =
                mock(ProxyServer.class);

        CommandManager commandManager =
                mock(CommandManager.class);

        CommandMeta.Builder builder =
                mock(CommandMeta.Builder.class);

        CommandMeta commandMeta =
                mock(CommandMeta.class);

        Object plugin = new Object();

        ProxyStatusCommand command =
                mock(ProxyStatusCommand.class);

        when(proxyServer.getCommandManager())
                .thenReturn(commandManager);

        when(commandManager.metaBuilder(
                "theosferaproxy"
        )).thenReturn(builder);

        when(builder.plugin(plugin))
                .thenReturn(builder);

        when(builder.build())
                .thenReturn(commandMeta);

        ProxyStatusCommandRegistration registration =
                new ProxyStatusCommandRegistration(
                        proxyServer,
                        plugin,
                        command
                );

        registration.register();

        verify(commandManager).metaBuilder(
                "theosferaproxy"
        );
        verify(builder).plugin(plugin);
        verify(commandManager).register(
                commandMeta,
                command
        );
    }

    @Test
    void unregistersAdministrativeProxyCommand() {
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

        when(commandManager.metaBuilder(
                "theosferaproxy"
        )).thenReturn(builder);

        when(builder.plugin(any()))
                .thenReturn(builder);

        when(builder.build())
                .thenReturn(commandMeta);

        ProxyStatusCommandRegistration registration =
                new ProxyStatusCommandRegistration(
                        proxyServer,
                        new Object(),
                        mock(ProxyStatusCommand.class)
                );

        registration.register();
        registration.unregister();

        verify(commandManager).unregister(commandMeta);
    }

    @Test
    void ignoresUnregisterBeforeRegistration() {
        ProxyServer proxyServer =
                mock(ProxyServer.class);

        CommandManager commandManager =
                mock(CommandManager.class);

        when(proxyServer.getCommandManager())
                .thenReturn(commandManager);

        ProxyStatusCommandRegistration registration =
                new ProxyStatusCommandRegistration(
                        proxyServer,
                        new Object(),
                        mock(ProxyStatusCommand.class)
                );

        registration.unregister();

        verify(commandManager, never())
                .unregister(any(CommandMeta.class));
    }
}

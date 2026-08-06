package com.theosfera.proxy.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RawServerCommandHardeningTest {

    @Test
    void installRemovesVelocityAliasAndRegistersGuardOnce() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        CommandManager commandManager = mock(CommandManager.class);
        EventManager eventManager = mock(EventManager.class);
        Object plugin = new Object();

        when(proxyServer.getCommandManager()).thenReturn(commandManager);
        when(proxyServer.getEventManager()).thenReturn(eventManager);

        RawServerCommandHardening hardening =
                new RawServerCommandHardening(proxyServer, plugin);

        hardening.install();
        hardening.install();

        verify(commandManager, times(1)).unregister("server");
        verify(eventManager, times(1)).register(plugin, hardening);
    }

    @Test
    void uninstallRemovesGuardListenerOnce() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        CommandManager commandManager = mock(CommandManager.class);
        EventManager eventManager = mock(EventManager.class);
        Object plugin = new Object();

        when(proxyServer.getCommandManager()).thenReturn(commandManager);
        when(proxyServer.getEventManager()).thenReturn(eventManager);

        RawServerCommandHardening hardening =
                new RawServerCommandHardening(proxyServer, plugin);

        hardening.install();
        hardening.uninstall();
        hardening.uninstall();

        verify(eventManager, times(1))
                .unregisterListener(plugin, hardening);
    }

    @Test
    void uninstallBeforeInstallDoesNothing() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        EventManager eventManager = mock(EventManager.class);
        Object plugin = new Object();

        when(proxyServer.getEventManager()).thenReturn(eventManager);

        RawServerCommandHardening hardening =
                new RawServerCommandHardening(proxyServer, plugin);

        hardening.uninstall();

        verify(eventManager, never())
                .unregisterListener(plugin, hardening);
    }

    @Test
    void blocksServerCommandForPlayerRegardlessOfPermission() {
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);

        CommandExecuteEvent event =
                new CommandExecuteEvent(
                        player,
                        "server lobby-2"
                );

        hardening().onCommandExecute(event);

        assertFalse(event.getResult().isAllowed());
        verify(player).sendMessage(
                RawServerCommandHardening.BLOCKED_MESSAGE
        );
    }

    @Test
    void blocksMixedCaseAndLeadingWhitespace() {
        Player player = mock(Player.class);
        CommandExecuteEvent event =
                new CommandExecuteEvent(
                        player,
                        "   SeRvEr\tlobby-2"
                );

        hardening().onCommandExecute(event);

        assertFalse(event.getResult().isAllowed());
        verify(player).sendMessage(
                RawServerCommandHardening.BLOCKED_MESSAGE
        );
    }

    @Test
    void blocksCommandRewrittenToServerByEarlierListener() {
        Player player = mock(Player.class);
        CommandExecuteEvent event =
                new CommandExecuteEvent(
                        player,
                        "network-switch"
                );
        event.setResult(
                CommandExecuteEvent.CommandResult.command(
                        "server lobby-2"
                )
        );

        hardening().onCommandExecute(event);

        assertFalse(event.getResult().isAllowed());
        verify(player).sendMessage(
                RawServerCommandHardening.BLOCKED_MESSAGE
        );
    }

    @Test
    void doesNotBlockDifferentPlayerCommands() {
        Player player = mock(Player.class);
        CommandExecuteEvent event =
                new CommandExecuteEvent(
                        player,
                        "serverlist"
                );

        hardening().onCommandExecute(event);

        assertTrue(event.getResult().isAllowed());
        verify(player, never()).sendMessage(
                RawServerCommandHardening.BLOCKED_MESSAGE
        );
    }

    @Test
    void doesNotBlockConsoleSource() {
        CommandSource console = mock(CommandSource.class);
        CommandExecuteEvent event =
                new CommandExecuteEvent(
                        console,
                        "server lobby-2"
                );

        hardening().onCommandExecute(event);

        assertTrue(event.getResult().isAllowed());
    }

    @Test
    void parserRecognizesOnlyRawServerRootToken() {
        assertTrue(RawServerCommandHardening.isRawServerCommand("server"));
        assertTrue(RawServerCommandHardening.isRawServerCommand("server lobby-2"));
        assertTrue(RawServerCommandHardening.isRawServerCommand("  SERVER lobby-2"));
        assertTrue(RawServerCommandHardening.isRawServerCommand("\tserver\tlobby-2"));

        assertFalse(RawServerCommandHardening.isRawServerCommand(""));
        assertFalse(RawServerCommandHardening.isRawServerCommand("   "));
        assertFalse(RawServerCommandHardening.isRawServerCommand("serverlist"));
        assertFalse(RawServerCommandHardening.isRawServerCommand("theosfera transfer skyblock"));
        assertFalse(RawServerCommandHardening.isRawServerCommand("lobby switch"));
    }

    private static RawServerCommandHardening hardening() {
        return new RawServerCommandHardening(
                mock(ProxyServer.class),
                new Object()
        );
    }
}

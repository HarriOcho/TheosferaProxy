package com.theosfera.proxy;

import com.theosfera.proxy.command.LobbyCommand;
import com.theosfera.proxy.failover.BackendKickFailoverListener;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
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

        Scheduler velocityScheduler =
                mock(Scheduler.class);

        Scheduler.TaskBuilder taskBuilder =
                mock(Scheduler.TaskBuilder.class);

        ScheduledTask scheduledTask =
                mock(ScheduledTask.class);

        when(proxyServer.getChannelRegistrar())
                .thenReturn(channelRegistrar);

        when(proxyServer.getEventManager())
                .thenReturn(eventManager);

        when(proxyServer.getCommandManager())
                .thenReturn(commandManager);

        when(proxyServer.getScheduler())
                .thenReturn(velocityScheduler);

        when(velocityScheduler.buildTask(
                any(),
                any(Runnable.class)
        )).thenReturn(taskBuilder);

        when(taskBuilder.repeat(any()))
                .thenReturn(taskBuilder);

        when(taskBuilder.schedule())
                .thenReturn(scheduledTask);

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

        ArgumentCaptor<BackendKickFailoverListener> listenerCaptor =
                ArgumentCaptor.forClass(
                        BackendKickFailoverListener.class
                );

        verify(eventManager).register(
                eq(plugin),
                listenerCaptor.capture()
        );

        BackendKickFailoverListener registeredListener =
                listenerCaptor.getValue();

        plugin.onProxyShutdown(null);

        verify(commandManager).unregister(commandMeta);

        verify(eventManager).unregisterListener(
                eq(plugin),
                same(registeredListener)
        );
    }
}

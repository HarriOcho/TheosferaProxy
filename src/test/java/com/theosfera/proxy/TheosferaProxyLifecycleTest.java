package com.theosfera.proxy;

import com.theosfera.proxy.command.LobbyCommand;
import com.theosfera.proxy.command.ProxyStatusCommand;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.failover.BackendKickFailoverListener;
import com.theosfera.proxy.messaging.ProtocolMessageDispatcher;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.messaging.ProtocolMessageListener;
import com.theosfera.proxy.messaging.handler.PlayerAuthenticatedMessageHandler;
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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void registersAndUnregistersCommandsInLifecycle() {
        ProxyServer proxyServer =
                mock(ProxyServer.class);

        ChannelRegistrar channelRegistrar =
                mock(ChannelRegistrar.class);

        EventManager eventManager =
                mock(EventManager.class);

        CommandManager commandManager =
                mock(CommandManager.class);

        CommandMeta.Builder lobbyBuilder =
                mock(CommandMeta.Builder.class);

        CommandMeta lobbyCommandMeta =
                mock(CommandMeta.class);

        CommandMeta.Builder proxyStatusBuilder =
                mock(CommandMeta.Builder.class);

        CommandMeta proxyStatusCommandMeta =
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
                .thenReturn(lobbyBuilder);

        when(lobbyBuilder.aliases("lobby"))
                .thenReturn(lobbyBuilder);

        when(lobbyBuilder.plugin(any()))
                .thenReturn(lobbyBuilder);

        when(lobbyBuilder.build())
                .thenReturn(lobbyCommandMeta);

        when(commandManager.metaBuilder(
                "theosferaproxy"
        )).thenReturn(proxyStatusBuilder);

        when(proxyStatusBuilder.plugin(any()))
                .thenReturn(proxyStatusBuilder);

        when(proxyStatusBuilder.build())
                .thenReturn(proxyStatusCommandMeta);

        TheosferaProxy plugin =
                new TheosferaProxy(
                        proxyServer,
                        mock(Logger.class),
                        temporaryDirectory
                );

        plugin.onProxyInitialization(null);

        verify(commandManager).register(
                eq(lobbyCommandMeta),
                any(LobbyCommand.class)
        );

        verify(commandManager).register(
                eq(proxyStatusCommandMeta),
                any(ProxyStatusCommand.class)
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

        verify(commandManager).unregister(lobbyCommandMeta);
        verify(commandManager).unregister(
                proxyStatusCommandMeta
        );

        verify(eventManager).unregisterListener(
                eq(plugin),
                same(registeredListener)
        );
    }

    @Test
    void lifecycleUsesConfiguredProxyInstanceIdentity()
            throws Exception {
        Files.writeString(
                temporaryDirectory.resolve(
                        "proxy-instance.properties"
                ),
                "proxy-name=proxy-configured",
                StandardCharsets.UTF_8
        );

        ProxyServer proxyServer =
                mock(ProxyServer.class);

        ChannelRegistrar channelRegistrar =
                mock(ChannelRegistrar.class);

        EventManager eventManager =
                mock(EventManager.class);

        CommandManager commandManager =
                mock(CommandManager.class);

        CommandMeta.Builder lobbyBuilder =
                mock(CommandMeta.Builder.class);

        CommandMeta lobbyCommandMeta =
                mock(CommandMeta.class);

        CommandMeta.Builder proxyStatusBuilder =
                mock(CommandMeta.Builder.class);

        CommandMeta proxyStatusCommandMeta =
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
                .thenReturn(lobbyBuilder);

        when(lobbyBuilder.aliases("lobby"))
                .thenReturn(lobbyBuilder);

        when(lobbyBuilder.plugin(any()))
                .thenReturn(lobbyBuilder);

        when(lobbyBuilder.build())
                .thenReturn(lobbyCommandMeta);

        when(commandManager.metaBuilder(
                "theosferaproxy"
        )).thenReturn(proxyStatusBuilder);

        when(proxyStatusBuilder.plugin(any()))
                .thenReturn(proxyStatusBuilder);

        when(proxyStatusBuilder.build())
                .thenReturn(proxyStatusCommandMeta);

        TheosferaProxy plugin =
                new TheosferaProxy(
                        proxyServer,
                        mock(Logger.class),
                        temporaryDirectory
                );

        plugin.onProxyInitialization(null);

        ArgumentCaptor<ProtocolMessageListener> listenerCaptor =
                ArgumentCaptor.forClass(
                        ProtocolMessageListener.class
                );

        verify(eventManager).register(
                eq(plugin),
                listenerCaptor.capture()
        );

        PlayerAuthenticatedMessageHandler handler =
                playerAuthenticatedHandlerFrom(
                        listenerCaptor.getValue()
                );

        ProxyInstanceIdentity identity =
                proxyIdentityFrom(handler);

        assertEquals(
                "proxy-configured",
                identity.proxyName()
        );
        assertNotEquals(
                "theosfera-proxy-local",
                identity.proxyName()
        );

        plugin.onProxyShutdown(null);
    }

    private PlayerAuthenticatedMessageHandler
    playerAuthenticatedHandlerFrom(
            ProtocolMessageListener listener
    ) throws ReflectiveOperationException {
        Field dispatcherField =
                ProtocolMessageListener.class
                        .getDeclaredField("dispatcher");
        dispatcherField.setAccessible(true);

        ProtocolMessageDispatcher dispatcher =
                (ProtocolMessageDispatcher)
                        dispatcherField.get(listener);

        Field handlersField =
                ProtocolMessageDispatcher.class
                        .getDeclaredField("handlers");
        handlersField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, ProtocolMessageHandler> handlers =
                (Map<String, ProtocolMessageHandler>)
                        handlersField.get(dispatcher);

        return (PlayerAuthenticatedMessageHandler)
                handlers.get("PLAYER_AUTHENTICATED");
    }

    private ProxyInstanceIdentity proxyIdentityFrom(
            PlayerAuthenticatedMessageHandler handler
    ) throws ReflectiveOperationException {
        Field identityField =
                PlayerAuthenticatedMessageHandler.class
                        .getDeclaredField("proxyIdentity");
        identityField.setAccessible(true);

        return (ProxyInstanceIdentity)
                identityField.get(handler);
    }
}

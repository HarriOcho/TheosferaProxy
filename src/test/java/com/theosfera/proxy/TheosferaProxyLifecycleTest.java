package com.theosfera.proxy;

import com.theosfera.proxy.command.LobbyCommand;
import com.theosfera.proxy.command.ProxyStatusCommand;
import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.PlayerPresenceCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.distributed.redis.RedisBackendCapacityCoordinator;
import com.theosfera.proxy.coordination.distributed.redis.RedisBackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.distributed.redis.RedisCoordinationConfig;
import com.theosfera.proxy.coordination.velocity.VelocityRedisCoordinationBootstrap;
import com.theosfera.proxy.failover.BackendKickFailoverListener;
import com.theosfera.proxy.messaging.ProtocolMessageDispatcher;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.messaging.ProtocolMessageListener;
import com.theosfera.proxy.messaging.handler.PlayerAuthenticatedMessageHandler;
import com.theosfera.proxy.session.PlayerDisconnectListener;
import com.theosfera.proxy.session.PlayerPresenceRuntimeService;
import com.theosfera.proxy.session.PlayerSessionReleaseService;
import com.theosfera.proxy.session.PlayerSessionRenewalService;
import com.theosfera.proxy.session.PlayerSessionShutdownReleaseService;
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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    void registersAndUnregistersCommandsInLifecycle() throws Exception {
        ProxyServer proxyServer = mock(ProxyServer.class);
        ChannelRegistrar channelRegistrar = mock(ChannelRegistrar.class);
        EventManager eventManager = mock(EventManager.class);
        CommandManager commandManager = mock(CommandManager.class);
        CommandMeta.Builder lobbyBuilder = mock(CommandMeta.Builder.class);
        CommandMeta lobbyCommandMeta = mock(CommandMeta.class);
        CommandMeta.Builder proxyStatusBuilder = mock(CommandMeta.Builder.class);
        CommandMeta proxyStatusCommandMeta = mock(CommandMeta.class);
        Scheduler velocityScheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);

        when(proxyServer.getChannelRegistrar()).thenReturn(channelRegistrar);
        when(proxyServer.getEventManager()).thenReturn(eventManager);
        when(proxyServer.getCommandManager()).thenReturn(commandManager);
        when(proxyServer.getScheduler()).thenReturn(velocityScheduler);
        when(velocityScheduler.buildTask(any(), any(Runnable.class)))
                .thenReturn(taskBuilder);
        when(taskBuilder.repeat(any())).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(scheduledTask);
        when(commandManager.metaBuilder("hub")).thenReturn(lobbyBuilder);
        when(lobbyBuilder.aliases("lobby")).thenReturn(lobbyBuilder);
        when(lobbyBuilder.plugin(any())).thenReturn(lobbyBuilder);
        when(lobbyBuilder.build()).thenReturn(lobbyCommandMeta);
        when(commandManager.metaBuilder("theosferaproxy"))
                .thenReturn(proxyStatusBuilder);
        when(proxyStatusBuilder.plugin(any())).thenReturn(proxyStatusBuilder);
        when(proxyStatusBuilder.build()).thenReturn(proxyStatusCommandMeta);

        TheosferaProxy plugin = new TheosferaProxy(
                proxyServer,
                mock(Logger.class),
                temporaryDirectory
        );

        RuntimeFixture fixture = prepareSessionRuntime(plugin);
        invokeNoArg(plugin, "initializeProxyInstanceIdentity");
        invokeNoArg(plugin, "initializeProtocolMessaging");
        invokeNoArg(plugin, "activateOperationalSurface");

        verify(fixture.sessionRenewalService()).start();
        verify(fixture.presenceRuntimeService()).start();
        verify(fixture.coordinationBootstrap())
                .createBackendCapacityCoordinator(
                        Duration.ofSeconds(20)
                );
        verify(fixture.coordinationBootstrap())
                .createBackendOccupancyCoordinator(any());

        verify(commandManager).register(
                eq(lobbyCommandMeta),
                any(LobbyCommand.class)
        );
        verify(commandManager).register(
                eq(proxyStatusCommandMeta),
                any(ProxyStatusCommand.class)
        );

        ArgumentCaptor<BackendKickFailoverListener> listenerCaptor =
                ArgumentCaptor.forClass(BackendKickFailoverListener.class);
        verify(eventManager).register(eq(plugin), listenerCaptor.capture());
        BackendKickFailoverListener registeredListener = listenerCaptor.getValue();

        invokeNoArg(plugin, "deactivateOperationalSurface");

        verify(fixture.presenceRuntimeService()).stop();
        verify(fixture.sessionRenewalService()).stop();
        verify(commandManager).unregister(lobbyCommandMeta);
        verify(commandManager).unregister(proxyStatusCommandMeta);
        verify(eventManager).unregisterListener(
                eq(plugin),
                same(registeredListener)
        );
    }

    @Test
    void lifecycleUsesConfiguredProxyInstanceIdentity() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("proxy-instance.properties"),
                "proxy-name=proxy-configured",
                StandardCharsets.UTF_8
        );

        ProxyServer proxyServer = mock(ProxyServer.class);
        ChannelRegistrar channelRegistrar = mock(ChannelRegistrar.class);
        EventManager eventManager = mock(EventManager.class);
        CommandManager commandManager = mock(CommandManager.class);
        CommandMeta.Builder lobbyBuilder = mock(CommandMeta.Builder.class);
        CommandMeta lobbyCommandMeta = mock(CommandMeta.class);
        CommandMeta.Builder proxyStatusBuilder = mock(CommandMeta.Builder.class);
        CommandMeta proxyStatusCommandMeta = mock(CommandMeta.class);
        Scheduler velocityScheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);

        when(proxyServer.getChannelRegistrar()).thenReturn(channelRegistrar);
        when(proxyServer.getEventManager()).thenReturn(eventManager);
        when(proxyServer.getCommandManager()).thenReturn(commandManager);
        when(proxyServer.getScheduler()).thenReturn(velocityScheduler);
        when(velocityScheduler.buildTask(any(), any(Runnable.class)))
                .thenReturn(taskBuilder);
        when(taskBuilder.repeat(any())).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(scheduledTask);
        when(commandManager.metaBuilder("hub")).thenReturn(lobbyBuilder);
        when(lobbyBuilder.aliases("lobby")).thenReturn(lobbyBuilder);
        when(lobbyBuilder.plugin(any())).thenReturn(lobbyBuilder);
        when(lobbyBuilder.build()).thenReturn(lobbyCommandMeta);
        when(commandManager.metaBuilder("theosferaproxy"))
                .thenReturn(proxyStatusBuilder);
        when(proxyStatusBuilder.plugin(any())).thenReturn(proxyStatusBuilder);
        when(proxyStatusBuilder.build()).thenReturn(proxyStatusCommandMeta);

        TheosferaProxy plugin = new TheosferaProxy(
                proxyServer,
                mock(Logger.class),
                temporaryDirectory
        );

        prepareSessionRuntime(plugin);
        invokeNoArg(plugin, "initializeProxyInstanceIdentity");
        invokeNoArg(plugin, "initializeProtocolMessaging");

        ProtocolMessageListener listener = protocolMessageListenerFrom(plugin);
        PlayerAuthenticatedMessageHandler handler =
                playerAuthenticatedHandlerFrom(listener);
        ProxyInstanceIdentity identity = proxyIdentityFrom(handler);

        assertEquals("proxy-configured", identity.proxyName());
        assertNotEquals("theosfera-proxy-local", identity.proxyName());
    }

    private RuntimeFixture prepareSessionRuntime(
            TheosferaProxy plugin
    ) throws ReflectiveOperationException {
        setField(
                plugin,
                "sessionCoordinator",
                mock(PlayerSessionCoordinator.class)
        );
        setField(
                plugin,
                "presenceCoordinator",
                mock(PlayerPresenceCoordinator.class)
        );
        setField(
                plugin,
                "releaseService",
                mock(PlayerSessionReleaseService.class)
        );
        setField(
                plugin,
                "playerDisconnectListener",
                mock(PlayerDisconnectListener.class)
        );
        setField(
                plugin,
                "shutdownReleaseService",
                mock(PlayerSessionShutdownReleaseService.class)
        );

        PlayerSessionRenewalService renewalService =
                mock(PlayerSessionRenewalService.class);
        setField(plugin, "sessionRenewalService", renewalService);

        PlayerPresenceRuntimeService presenceRuntimeService =
                mock(PlayerPresenceRuntimeService.class);
        setField(plugin, "presenceRuntimeService", presenceRuntimeService);

        VelocityRedisCoordinationBootstrap coordinationBootstrap =
                mock(VelocityRedisCoordinationBootstrap.class);
        when(coordinationBootstrap.state())
                .thenReturn(CoordinationState.HEALTHY);
        when(coordinationBootstrap.config())
                .thenReturn(
                        new RedisCoordinationConfig(
                                "redis://127.0.0.1:6379",
                                Duration.ofSeconds(15),
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(20)
                        )
                );
        when(coordinationBootstrap.createBackendOccupancyCoordinator(any()))
                .thenReturn(mock(RedisBackendOccupancyCoordinator.class));
        when(coordinationBootstrap.createBackendCapacityCoordinator(
                Duration.ofSeconds(20)
        )).thenReturn(mock(RedisBackendCapacityCoordinator.class));
        setField(
                plugin,
                "coordinationBootstrap",
                coordinationBootstrap
        );

        return new RuntimeFixture(
                renewalService,
                presenceRuntimeService,
                coordinationBootstrap
        );
    }

    private void setField(
            TheosferaProxy plugin,
            String fieldName,
            Object value
    ) throws ReflectiveOperationException {
        Field field = TheosferaProxy.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private void invokeNoArg(
            TheosferaProxy plugin,
            String methodName
    ) throws ReflectiveOperationException {
        Method method = TheosferaProxy.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(plugin);
    }

    private ProtocolMessageListener protocolMessageListenerFrom(
            TheosferaProxy plugin
    ) throws ReflectiveOperationException {
        Field listenerField = TheosferaProxy.class
                .getDeclaredField("protocolMessageListener");
        listenerField.setAccessible(true);
        return (ProtocolMessageListener) listenerField.get(plugin);
    }

    private PlayerAuthenticatedMessageHandler playerAuthenticatedHandlerFrom(
            ProtocolMessageListener listener
    ) throws ReflectiveOperationException {
        Field dispatcherField = ProtocolMessageListener.class
                .getDeclaredField("dispatcher");
        dispatcherField.setAccessible(true);

        ProtocolMessageDispatcher dispatcher =
                (ProtocolMessageDispatcher) dispatcherField.get(listener);

        Field handlersField = ProtocolMessageDispatcher.class
                .getDeclaredField("handlers");
        handlersField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, ProtocolMessageHandler> handlers =
                (Map<String, ProtocolMessageHandler>) handlersField.get(dispatcher);

        return (PlayerAuthenticatedMessageHandler)
                handlers.get("PLAYER_AUTHENTICATED");
    }

    private ProxyInstanceIdentity proxyIdentityFrom(
            PlayerAuthenticatedMessageHandler handler
    ) throws ReflectiveOperationException {
        Field identityField = PlayerAuthenticatedMessageHandler.class
                .getDeclaredField("proxyIdentity");
        identityField.setAccessible(true);
        return (ProxyInstanceIdentity) identityField.get(handler);
    }

    private record RuntimeFixture(
            PlayerSessionRenewalService sessionRenewalService,
            PlayerPresenceRuntimeService presenceRuntimeService,
            VelocityRedisCoordinationBootstrap coordinationBootstrap
    ) {
    }
}

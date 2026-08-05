package com.theosfera.proxy;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.distributed.redis.RedisBackendCapacityCoordinator;
import com.theosfera.proxy.coordination.distributed.redis.RedisBackendOccupancyCoordinator;
import com.theosfera.proxy.coordination.distributed.redis.RedisCoordinationConfig;
import com.theosfera.proxy.coordination.velocity.VelocityRedisCoordinationBootstrap;
import com.theosfera.proxy.transfer.DistributedBackendCapacityRuntime;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TheosferaProxyDistributedCapacityCompositionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void composesAuthoritativeCoordinatorsWithConfiguredReservationTtl()
            throws Exception {
        TheosferaProxy plugin = plugin();
        VelocityRedisCoordinationBootstrap bootstrap =
                healthyBootstrap();
        setField(plugin, "coordinationBootstrap", bootstrap);

        BackendAuthorizationPolicy policy = policy();
        TransferTargetResolver resolver =
                mock(TransferTargetResolver.class);

        invokeCapacityInitialization(plugin, policy, resolver);

        verify(bootstrap).createBackendOccupancyCoordinator(
                Set.of("lobby-1")
        );
        verify(bootstrap).createBackendCapacityCoordinator(
                Duration.ofSeconds(20)
        );

        Field runtimeField = TheosferaProxy.class
                .getDeclaredField("distributedBackendCapacityRuntime");
        runtimeField.setAccessible(true);
        assertNotNull(
                (DistributedBackendCapacityRuntime) runtimeField.get(plugin)
        );
    }

    @Test
    void refusesCompositionWhenRedisCoordinationIsNotHealthy()
            throws Exception {
        TheosferaProxy plugin = plugin();
        VelocityRedisCoordinationBootstrap bootstrap =
                mock(VelocityRedisCoordinationBootstrap.class);
        when(bootstrap.state()).thenReturn(CoordinationState.DEGRADED);
        setField(plugin, "coordinationBootstrap", bootstrap);

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> invokeCapacityInitialization(
                        plugin,
                        policy(),
                        mock(TransferTargetResolver.class)
                )
        );

        assertInstanceOf(
                IllegalStateException.class,
                failure.getCause()
        );
    }

    private TheosferaProxy plugin() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        when(proxyServer.getChannelRegistrar())
                .thenReturn(mock(ChannelRegistrar.class));
        return new TheosferaProxy(
                proxyServer,
                mock(Logger.class),
                temporaryDirectory
        );
    }

    private VelocityRedisCoordinationBootstrap healthyBootstrap() {
        VelocityRedisCoordinationBootstrap bootstrap =
                mock(VelocityRedisCoordinationBootstrap.class);
        when(bootstrap.state()).thenReturn(CoordinationState.HEALTHY);
        when(bootstrap.config()).thenReturn(
                new RedisCoordinationConfig(
                        "redis://127.0.0.1:6379",
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20)
                )
        );
        when(bootstrap.createBackendOccupancyCoordinator(
                Set.of("lobby-1")
        )).thenReturn(mock(RedisBackendOccupancyCoordinator.class));
        when(bootstrap.createBackendCapacityCoordinator(
                Duration.ofSeconds(20)
        )).thenReturn(mock(RedisBackendCapacityCoordinator.class));
        return bootstrap;
    }

    private BackendAuthorizationPolicy policy() {
        return new BackendAuthorizationPolicy(
                Map.of(
                        "lobby-1",
                        new BackendPolicyEntry(
                                BackendType.LOBBY,
                                100,
                                90
                        )
                )
        );
    }

    private void invokeCapacityInitialization(
            TheosferaProxy plugin,
            BackendAuthorizationPolicy policy,
            TransferTargetResolver resolver
    ) throws ReflectiveOperationException {
        Method method = TheosferaProxy.class.getDeclaredMethod(
                "initializeDistributedBackendCapacity",
                BackendAuthorizationPolicy.class,
                TransferTargetResolver.class
        );
        method.setAccessible(true);
        method.invoke(plugin, policy, resolver);
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
}

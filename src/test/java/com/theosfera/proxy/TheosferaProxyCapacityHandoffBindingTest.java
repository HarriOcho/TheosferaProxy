package com.theosfera.proxy;

import com.theosfera.proxy.coordination.PlayerPresenceCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.session.PlayerDisconnectListener;
import com.theosfera.proxy.session.PlayerPresenceRuntimeService;
import com.theosfera.proxy.session.PlayerSessionReleaseService;
import com.theosfera.proxy.session.PlayerSessionRenewalService;
import com.theosfera.proxy.session.PlayerSessionShutdownReleaseService;
import com.theosfera.proxy.transfer.BackendCapacityHandoffService;
import com.theosfera.proxy.transfer.DistributedBackendCapacityRuntime;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TheosferaProxyCapacityHandoffBindingTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void bindsOneSharedHandoffLifecycleToPresenceAndDisconnect()
            throws Exception {
        ProxyServer proxyServer = mock(ProxyServer.class);
        when(proxyServer.getChannelRegistrar())
                .thenReturn(mock(ChannelRegistrar.class));
        TheosferaProxy plugin = new TheosferaProxy(
                proxyServer,
                mock(Logger.class),
                temporaryDirectory
        );

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
                "sessionRenewalService",
                mock(PlayerSessionRenewalService.class)
        );
        setField(
                plugin,
                "shutdownReleaseService",
                mock(PlayerSessionShutdownReleaseService.class)
        );

        PlayerPresenceRuntimeService presenceRuntime =
                mock(PlayerPresenceRuntimeService.class);
        PlayerDisconnectListener disconnectListener =
                mock(PlayerDisconnectListener.class);
        setField(plugin, "presenceRuntimeService", presenceRuntime);
        setField(plugin, "playerDisconnectListener", disconnectListener);

        BackendCapacityHandoffService handoffService =
                mock(BackendCapacityHandoffService.class);
        DistributedBackendCapacityRuntime runtime =
                mock(DistributedBackendCapacityRuntime.class);
        when(runtime.handoffService()).thenReturn(handoffService);
        setField(plugin, "distributedBackendCapacityRuntime", runtime);

        Method bindMethod = TheosferaProxy.class.getDeclaredMethod(
                "bindDistributedCapacityHandoff"
        );
        bindMethod.setAccessible(true);
        bindMethod.invoke(plugin);

        verify(presenceRuntime).configureCapacityHandoffLifecycle(
                handoffService
        );
        verify(disconnectListener).configureCapacityHandoffLifecycle(
                handoffService
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
}

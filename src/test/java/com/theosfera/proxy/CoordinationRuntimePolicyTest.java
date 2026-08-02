package com.theosfera.proxy;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoordinationRuntimePolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesOperationalSurfaceWithoutDistributedSessionRuntime()
            throws Exception {
        ProxyServer proxyServer = mock(ProxyServer.class);
        ChannelRegistrar channelRegistrar = mock(ChannelRegistrar.class);
        when(proxyServer.getChannelRegistrar()).thenReturn(channelRegistrar);

        TheosferaProxy plugin = new TheosferaProxy(
                proxyServer,
                mock(Logger.class),
                temporaryDirectory
        );

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> invokeNoArg(plugin, "activateOperationalSurface")
        );

        assertInstanceOf(
                IllegalStateException.class,
                failure.getCause()
        );
    }

    private void invokeNoArg(
            TheosferaProxy plugin,
            String methodName
    ) throws ReflectiveOperationException {
        Method method = TheosferaProxy.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(plugin);
    }
}

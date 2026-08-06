package com.theosfera.proxy.control;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyControlServerTest {

    private static final InetSocketAddress BIND_ADDRESS =
            new InetSocketAddress("127.0.0.1", 25590);

    @Test
    void configuresTls13AndBindsListener() throws Exception {
        SSLServerSocketFactory factory =
                mock(SSLServerSocketFactory.class);
        SSLServerSocket serverSocket =
                mock(SSLServerSocket.class);
        Logger logger = mock(Logger.class);
        CountDownLatch acceptEntered = new CountDownLatch(1);
        CountDownLatch releaseAccept = new CountDownLatch(1);

        when(factory.createServerSocket()).thenReturn(serverSocket);
        when(serverSocket.getSupportedProtocols())
                .thenReturn(new String[]{"TLSv1.3", "TLSv1.2"});
        when(serverSocket.getLocalSocketAddress())
                .thenReturn(BIND_ADDRESS);
        when(serverSocket.accept()).thenAnswer(invocation -> {
            acceptEntered.countDown();
            try {
                releaseAccept.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            throw new SocketException("listener closed");
        });

        ProxyControlServer server = new ProxyControlServer(
                factory,
                BIND_ADDRESS,
                Duration.ofSeconds(3),
                mock(ControlConnectionAuthenticator.class),
                new BackendControlSessionRegistry(),
                mock(AuthenticatedControlConnectionHandler.class),
                logger,
                UUID::randomUUID
        );

        try {
            server.start();
            assertTrue(acceptEntered.await(1, TimeUnit.SECONDS));

            verify(serverSocket).setEnabledProtocols(
                    new String[]{"TLSv1.3"}
            );
            verify(serverSocket).setUseClientMode(false);
            verify(serverSocket).setNeedClientAuth(false);
            verify(serverSocket).setReuseAddress(true);
            verify(serverSocket).bind(BIND_ADDRESS);
        } finally {
            releaseAccept.countDown();
            server.stop();
        }

        assertFalse(server.isRunning());
        verify(serverSocket, atLeastOnce()).close();
    }

    @Test
    void rejectsListenerWithoutTls13Support() throws Exception {
        SSLServerSocketFactory factory =
                mock(SSLServerSocketFactory.class);
        SSLServerSocket serverSocket =
                mock(SSLServerSocket.class);

        when(factory.createServerSocket()).thenReturn(serverSocket);
        when(serverSocket.getSupportedProtocols())
                .thenReturn(new String[]{"TLSv1.2"});

        ProxyControlServer server = new ProxyControlServer(
                factory,
                BIND_ADDRESS,
                Duration.ofSeconds(3),
                mock(ControlConnectionAuthenticator.class),
                new BackendControlSessionRegistry(),
                mock(AuthenticatedControlConnectionHandler.class),
                mock(Logger.class),
                UUID::randomUUID
        );

        assertThrows(
                IllegalStateException.class,
                server::start
        );

        verify(serverSocket).close();
        assertFalse(server.isRunning());
    }

    @Test
    void authenticatesAcceptedTlsConnectionOffAcceptLoop()
            throws Exception {
        SSLServerSocketFactory factory =
                mock(SSLServerSocketFactory.class);
        SSLServerSocket serverSocket =
                mock(SSLServerSocket.class);
        SSLSocket clientSocket = mock(SSLSocket.class);
        Logger logger = mock(Logger.class);
        ControlConnectionAuthenticator authenticator =
                mock(ControlConnectionAuthenticator.class);
        AuthenticatedControlConnectionHandler authenticatedHandler =
                mock(AuthenticatedControlConnectionHandler.class);
        BackendControlSessionRegistry sessionRegistry =
                new BackendControlSessionRegistry();
        UUID connectionId = UUID.randomUUID();
        CountDownLatch handlerCalled = new CountDownLatch(1);
        CountDownLatch releaseAccept = new CountDownLatch(1);

        when(factory.createServerSocket()).thenReturn(serverSocket);
        when(serverSocket.getSupportedProtocols())
                .thenReturn(new String[]{"TLSv1.3"});
        when(serverSocket.getLocalSocketAddress())
                .thenReturn(BIND_ADDRESS);
        when(serverSocket.accept())
                .thenReturn(clientSocket)
                .thenAnswer(invocation -> {
                    try {
                        releaseAccept.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    throw new SocketException("listener closed");
                });

        when(clientSocket.getSupportedProtocols())
                .thenReturn(new String[]{"TLSv1.3"});
        when(clientSocket.getInputStream())
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        when(clientSocket.getOutputStream())
                .thenReturn(new ByteArrayOutputStream());

        BackendControlSessionRegistration registration =
                sessionRegistry.register(
                        connectionId,
                        new BackendIdentity(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                );

        when(authenticator.authenticate(
                eq(connectionId),
                any(),
                any()
        )).thenReturn(Optional.of(registration));

        org.mockito.Mockito.doAnswer(invocation -> {
            handlerCalled.countDown();
            return null;
        }).when(authenticatedHandler).handle(
                eq(registration.current()),
                any(),
                any()
        );

        ProxyControlServer server = new ProxyControlServer(
                factory,
                BIND_ADDRESS,
                Duration.ofSeconds(3),
                authenticator,
                sessionRegistry,
                authenticatedHandler,
                logger,
                () -> connectionId
        );

        try {
            server.start();
            assertTrue(handlerCalled.await(1, TimeUnit.SECONDS));

            verify(clientSocket).setUseClientMode(false);
            verify(clientSocket).setEnabledProtocols(
                    new String[]{"TLSv1.3"}
            );
            verify(clientSocket).setKeepAlive(true);
            verify(clientSocket).setSoTimeout(3000);
            verify(clientSocket).startHandshake();
            verify(clientSocket).setSoTimeout(0);
            verify(authenticatedHandler).handle(
                    eq(registration.current()),
                    any(),
                    any()
            );
        } finally {
            releaseAccept.countDown();
            server.stop();
        }
    }

    @Test
    void rejectedAuthenticationNeverReachesAuthenticatedHandler()
            throws Exception {
        SSLServerSocketFactory factory =
                mock(SSLServerSocketFactory.class);
        SSLServerSocket serverSocket =
                mock(SSLServerSocket.class);
        SSLSocket clientSocket = mock(SSLSocket.class);
        ControlConnectionAuthenticator authenticator =
                mock(ControlConnectionAuthenticator.class);
        AuthenticatedControlConnectionHandler authenticatedHandler =
                mock(AuthenticatedControlConnectionHandler.class);
        UUID connectionId = UUID.randomUUID();
        CountDownLatch authenticationCalled = new CountDownLatch(1);
        CountDownLatch releaseAccept = new CountDownLatch(1);

        when(factory.createServerSocket()).thenReturn(serverSocket);
        when(serverSocket.getSupportedProtocols())
                .thenReturn(new String[]{"TLSv1.3"});
        when(serverSocket.getLocalSocketAddress())
                .thenReturn(BIND_ADDRESS);
        when(serverSocket.accept())
                .thenReturn(clientSocket)
                .thenAnswer(invocation -> {
                    try {
                        releaseAccept.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    throw new SocketException("listener closed");
                });
        when(clientSocket.getSupportedProtocols())
                .thenReturn(new String[]{"TLSv1.3"});
        when(clientSocket.getInputStream())
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        when(clientSocket.getOutputStream())
                .thenReturn(new ByteArrayOutputStream());

        when(authenticator.authenticate(
                eq(connectionId),
                any(),
                any()
        )).thenAnswer(invocation -> {
            authenticationCalled.countDown();
            return Optional.empty();
        });

        ProxyControlServer server = new ProxyControlServer(
                factory,
                BIND_ADDRESS,
                Duration.ofSeconds(3),
                authenticator,
                new BackendControlSessionRegistry(),
                authenticatedHandler,
                mock(Logger.class),
                () -> connectionId
        );

        try {
            server.start();
            assertTrue(
                    authenticationCalled.await(
                            1,
                            TimeUnit.SECONDS
                    )
            );
        } finally {
            releaseAccept.countDown();
            server.stop();
        }

        verify(authenticatedHandler, never()).handle(
                any(),
                any(),
                any()
        );
    }

    @Test
    void validatesAuthenticationTimeout() {
        SSLServerSocketFactory factory =
                mock(SSLServerSocketFactory.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProxyControlServer(
                        factory,
                        BIND_ADDRESS,
                        Duration.ZERO,
                        mock(ControlConnectionAuthenticator.class),
                        new BackendControlSessionRegistry(),
                        mock(AuthenticatedControlConnectionHandler.class),
                        mock(Logger.class),
                        UUID::randomUUID
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProxyControlServer(
                        factory,
                        BIND_ADDRESS,
                        Duration.ofMillis(
                                (long) Integer.MAX_VALUE + 1L
                        ),
                        mock(ControlConnectionAuthenticator.class),
                        new BackendControlSessionRegistry(),
                        mock(AuthenticatedControlConnectionHandler.class),
                        mock(Logger.class),
                        UUID::randomUUID
                )
        );
    }
}

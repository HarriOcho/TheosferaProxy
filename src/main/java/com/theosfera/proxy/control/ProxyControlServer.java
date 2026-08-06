package com.theosfera.proxy.control;

import org.slf4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class ProxyControlServer implements AutoCloseable {

    private static final String REQUIRED_TLS_PROTOCOL = "TLSv1.3";
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 3L;

    private final SSLServerSocketFactory serverSocketFactory;
    private final InetSocketAddress bindAddress;
    private final int authenticationTimeoutMillis;
    private final ControlConnectionAuthenticator authenticator;
    private final BackendControlSessionRegistry sessionRegistry;
    private final AuthenticatedControlConnectionHandler authenticatedHandler;
    private final Logger logger;
    private final Supplier<UUID> connectionIdGenerator;
    private final Map<UUID, SSLSocket> liveSockets =
            new ConcurrentHashMap<>();

    private volatile SSLServerSocket serverSocket;
    private volatile ExecutorService acceptExecutor;
    private volatile ExecutorService connectionExecutor;
    private volatile boolean running;

    public ProxyControlServer(
            SSLContext sslContext,
            InetSocketAddress bindAddress,
            Duration authenticationTimeout,
            ControlConnectionAuthenticator authenticator,
            BackendControlSessionRegistry sessionRegistry,
            AuthenticatedControlConnectionHandler authenticatedHandler,
            Logger logger
    ) {
        this(
                Objects.requireNonNull(
                        sslContext,
                        "sslContext cannot be null"
                ).getServerSocketFactory(),
                bindAddress,
                authenticationTimeout,
                authenticator,
                sessionRegistry,
                authenticatedHandler,
                logger,
                UUID::randomUUID
        );
    }

    ProxyControlServer(
            SSLServerSocketFactory serverSocketFactory,
            InetSocketAddress bindAddress,
            Duration authenticationTimeout,
            ControlConnectionAuthenticator authenticator,
            BackendControlSessionRegistry sessionRegistry,
            AuthenticatedControlConnectionHandler authenticatedHandler,
            Logger logger,
            Supplier<UUID> connectionIdGenerator
    ) {
        this.serverSocketFactory = Objects.requireNonNull(
                serverSocketFactory,
                "serverSocketFactory cannot be null"
        );
        this.bindAddress = Objects.requireNonNull(
                bindAddress,
                "bindAddress cannot be null"
        );
        this.authenticationTimeoutMillis = requireTimeoutMillis(
                authenticationTimeout
        );
        this.authenticator = Objects.requireNonNull(
                authenticator,
                "authenticator cannot be null"
        );
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
        this.authenticatedHandler = Objects.requireNonNull(
                authenticatedHandler,
                "authenticatedHandler cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
        this.connectionIdGenerator = Objects.requireNonNull(
                connectionIdGenerator,
                "connectionIdGenerator cannot be null"
        );
    }

    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException(
                    "Proxy control server is already running"
            );
        }

        SSLServerSocket createdServerSocket = createServerSocket();
        ExecutorService createdAcceptExecutor = null;
        ExecutorService createdConnectionExecutor = null;

        try {
            createdServerSocket.bind(bindAddress);

            createdAcceptExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform()
                            .name("theosfera-control-accept")
                            .daemon(true)
                            .factory()
            );
            createdConnectionExecutor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual()
                            .name("theosfera-control-connection-", 0)
                            .factory()
            );

            serverSocket = createdServerSocket;
            acceptExecutor = createdAcceptExecutor;
            connectionExecutor = createdConnectionExecutor;
            running = true;

            createdAcceptExecutor.execute(this::acceptLoop);

            logger.info(
                    "Backend control TLS listener iniciado en {}.",
                    createdServerSocket.getLocalSocketAddress()
            );
        } catch (IOException | RuntimeException exception) {
            running = false;
            serverSocket = null;
            acceptExecutor = null;
            connectionExecutor = null;
            closeQuietly(createdServerSocket);
            shutdownExecutor(createdAcceptExecutor);
            shutdownExecutor(createdConnectionExecutor);
            throw exception;
        }
    }

    public synchronized void stop() {
        if (!running
                && serverSocket == null
                && acceptExecutor == null
                && connectionExecutor == null) {
            return;
        }

        running = false;

        SSLServerSocket socketToClose = serverSocket;
        ExecutorService acceptToStop = acceptExecutor;
        ExecutorService connectionsToStop = connectionExecutor;

        serverSocket = null;
        acceptExecutor = null;
        connectionExecutor = null;

        closeQuietly(socketToClose);
        liveSockets.values().forEach(
                ProxyControlServer::closeQuietly
        );
        liveSockets.clear();
        sessionRegistry.clear();

        shutdownExecutor(acceptToStop);
        shutdownExecutor(connectionsToStop);

        logger.info("Backend control TLS listener detenido.");
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public int liveConnectionCount() {
        return liveSockets.size();
    }

    private SSLServerSocket createServerSocket() throws IOException {
        ServerSocket rawServerSocket =
                serverSocketFactory.createServerSocket();

        if (!(rawServerSocket instanceof SSLServerSocket sslServerSocket)) {
            closeQuietly(rawServerSocket);
            throw new IllegalStateException(
                    "Control listener requires an SSLServerSocket"
            );
        }

        try {
            requireTls13(sslServerSocket.getSupportedProtocols());
            sslServerSocket.setEnabledProtocols(
                    new String[]{REQUIRED_TLS_PROTOCOL}
            );
            sslServerSocket.setUseClientMode(false);
            sslServerSocket.setNeedClientAuth(false);
            sslServerSocket.setReuseAddress(true);
            return sslServerSocket;
        } catch (RuntimeException exception) {
            closeQuietly(sslServerSocket);
            throw exception;
        }
    }

    private void acceptLoop() {
        while (running) {
            SSLServerSocket currentServerSocket = serverSocket;
            if (currentServerSocket == null) {
                return;
            }

            final Socket accepted;
            try {
                accepted = currentServerSocket.accept();
            } catch (SocketException exception) {
                if (!running) {
                    return;
                }
                logger.warn(
                        "El listener de control perdio su socket de escucha.",
                        exception
                );
                running = false;
                return;
            } catch (IOException exception) {
                if (running) {
                    logger.warn(
                            "Fallo al aceptar una conexion de control.",
                            exception
                    );
                }
                continue;
            }

            if (!(accepted instanceof SSLSocket sslSocket)) {
                closeQuietly(accepted);
                logger.warn(
                        "Se rechazo una conexion de control que no era TLS."
                );
                continue;
            }

            ExecutorService executor = connectionExecutor;
            if (!running || executor == null) {
                closeQuietly(sslSocket);
                return;
            }

            try {
                executor.execute(() -> handleConnection(sslSocket));
            } catch (RejectedExecutionException exception) {
                closeQuietly(sslSocket);
                if (running) {
                    logger.warn(
                            "No se pudo despachar una conexion de control.",
                            exception
                    );
                }
            }
        }
    }

    private void handleConnection(SSLSocket socket) {
        UUID connectionId = Objects.requireNonNull(
                connectionIdGenerator.get(),
                "connectionIdGenerator cannot return null"
        );
        BackendControlSession authenticatedSession = null;

        liveSockets.put(connectionId, socket);

        try (socket) {
            configureAcceptedSocket(socket);
            socket.startHandshake();

            Optional<BackendControlSessionRegistration> registration =
                    authenticator.authenticate(
                            connectionId,
                            socket.getInputStream(),
                            socket.getOutputStream()
                    );

            if (registration.isEmpty()) {
                logger.warn(
                        "Conexion de control {} rechazada durante autenticacion.",
                        connectionId
                );
                return;
            }

            BackendControlSessionRegistration acceptedRegistration =
                    registration.orElseThrow();
            authenticatedSession = acceptedRegistration.current();

            acceptedRegistration.previousOptional().ifPresent(
                    previous -> closeReplacedConnection(
                            previous,
                            connectionId
                    )
            );

            socket.setSoTimeout(0);

            logger.info(
                    "Backend {} autenticado en control channel (generation {}).",
                    authenticatedSession.identity().serverName(),
                    authenticatedSession.generation()
            );

            authenticatedHandler.handle(
                    authenticatedSession,
                    socket.getInputStream(),
                    socket.getOutputStream()
            );
        } catch (SocketTimeoutException exception) {
            logger.warn(
                    "Conexion de control {} excedio el timeout de autenticacion.",
                    connectionId
            );
        } catch (SSLException exception) {
            logger.warn(
                    "Handshake TLS rechazado para conexion de control {}.",
                    connectionId
            );
        } catch (ControlConnectionProtocolException exception) {
            logger.warn(
                    "Conexion de control {} cerrada por violacion de protocolo: {}.",
                    connectionId,
                    exception.getMessage()
            );
        } catch (IOException exception) {
            if (running) {
                logger.warn(
                        "Conexion de control {} terminada por error de I/O.",
                        connectionId,
                        exception
                );
            }
        } catch (RuntimeException exception) {
            logger.warn(
                    "Conexion de control {} terminada por error interno.",
                    connectionId,
                    exception
            );
        } finally {
            liveSockets.remove(connectionId, socket);

            if (authenticatedSession != null) {
                boolean removed = sessionRegistry.removeIfCurrent(
                        authenticatedSession
                );

                if (removed) {
                    logger.info(
                            "Backend {} perdio su sesion de control (generation {}).",
                            authenticatedSession.identity().serverName(),
                            authenticatedSession.generation()
                    );
                }
            }
        }
    }

    private void configureAcceptedSocket(
            SSLSocket socket
    ) throws SocketException {
        requireTls13(socket.getSupportedProtocols());
        socket.setUseClientMode(false);
        socket.setEnabledProtocols(
                new String[]{REQUIRED_TLS_PROTOCOL}
        );
        socket.setKeepAlive(true);
        socket.setSoTimeout(authenticationTimeoutMillis);
    }

    private void closeReplacedConnection(
            BackendControlSession previous,
            UUID currentConnectionId
    ) {
        UUID previousConnectionId = previous.connectionId();
        if (previousConnectionId.equals(currentConnectionId)) {
            return;
        }

        SSLSocket previousSocket = liveSockets.get(
                previousConnectionId
        );

        if (previousSocket != null) {
            closeQuietly(previousSocket);
        }
    }

    private static void requireTls13(String[] supportedProtocols) {
        if (Arrays.stream(supportedProtocols)
                .noneMatch(REQUIRED_TLS_PROTOCOL::equals)) {
            throw new IllegalStateException(
                    "TLSv1.3 is required for the backend control channel"
            );
        }
    }

    private static int requireTimeoutMillis(Duration timeout) {
        Duration nonNullTimeout = Objects.requireNonNull(
                timeout,
                "authenticationTimeout cannot be null"
        );

        if (nonNullTimeout.isZero() || nonNullTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "authenticationTimeout must be positive"
            );
        }

        long millis = nonNullTimeout.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "authenticationTimeout must fit in Socket SO_TIMEOUT"
            );
        }

        return (int) millis;
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }

        executor.shutdownNow();
        try {
            executor.awaitTermination(
                    EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort cleanup during connection/lifecycle teardown.
        }
    }
}

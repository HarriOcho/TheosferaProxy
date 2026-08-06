package com.theosfera.proxy.control;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.transport.ProtocolFrameCodec;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import org.slf4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

public final class BackendControlRuntime implements AutoCloseable {

    private final BackendControlConfig config;
    private final ControlAuthenticationService authenticationService;
    private final BackendControlSessionRegistry sessionRegistry;
    private final FileBackendControlSecretProvider secretProvider;
    private final ProxyControlServer server;
    private final Logger logger;

    private boolean started;
    private boolean closed;

    private BackendControlRuntime(
            BackendControlConfig config,
            ControlAuthenticationService authenticationService,
            BackendControlSessionRegistry sessionRegistry,
            FileBackendControlSecretProvider secretProvider,
            ProxyControlServer server,
            Logger logger
    ) {
        this.config = Objects.requireNonNull(
                config,
                "config cannot be null"
        );
        this.authenticationService = authenticationService;
        this.sessionRegistry = sessionRegistry;
        this.secretProvider = secretProvider;
        this.server = server;
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public static BackendControlRuntime create(
            Path dataDirectory,
            BackendAuthorizationPolicy authorizationPolicy,
            Logger logger
    ) {
        return create(
                dataDirectory,
                authorizationPolicy,
                logger,
                System::getenv
        );
    }

    static BackendControlRuntime create(
            Path dataDirectory,
            BackendAuthorizationPolicy authorizationPolicy,
            Logger logger,
            Function<String, String> environmentReader
    ) {
        Path nonNullDataDirectory = Objects.requireNonNull(
                dataDirectory,
                "dataDirectory cannot be null"
        );
        BackendAuthorizationPolicy nonNullPolicy =
                Objects.requireNonNull(
                        authorizationPolicy,
                        "authorizationPolicy cannot be null"
                );
        Logger nonNullLogger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
        Function<String, String> nonNullEnvironmentReader =
                Objects.requireNonNull(
                        environmentReader,
                        "environmentReader cannot be null"
                );

        BackendControlConfig config =
                new BackendControlConfigLoader(
                        nonNullDataDirectory
                ).load();

        if (!config.enabled()) {
            return new BackendControlRuntime(
                    config,
                    null,
                    null,
                    null,
                    null,
                    nonNullLogger
            );
        }

        String passwordValue = nonNullEnvironmentReader.apply(
                config.keyStorePasswordEnvironmentVariable()
        );

        if (passwordValue == null || passwordValue.isEmpty()) {
            throw new IllegalStateException(
                    "Backend control keystore password environment variable is missing: "
                            + config.keyStorePasswordEnvironmentVariable()
            );
        }

        FileBackendControlSecretProvider secretProvider =
                FileBackendControlSecretProvider.load(
                        config.secretsFile(),
                        nonNullPolicy.authorizedBackendNames()
                );

        char[] keyStorePassword = passwordValue.toCharArray();
        final SSLContext sslContext;
        try {
            sslContext = new ControlTlsContextFactory()
                    .createServerContext(
                            config.keyStorePath(),
                            keyStorePassword
                    );
        } catch (RuntimeException exception) {
            secretProvider.close();
            throw exception;
        } finally {
            Arrays.fill(keyStorePassword, '\0');
        }

        Clock clock = Clock.systemUTC();
        ProtocolJsonCodec jsonCodec = new ProtocolJsonCodec();
        ProtocolFrameCodec frameCodec = new ProtocolFrameCodec();
        BackendControlSessionRegistry sessionRegistry =
                new BackendControlSessionRegistry();
        ControlAuthenticationService authenticationService =
                new ControlAuthenticationService(
                        clock,
                        config.authenticationTimeout(),
                        nonNullPolicy,
                        secretProvider
                );
        ControlConnectionHandshakeHandler authenticator =
                new ControlConnectionHandshakeHandler(
                        clock,
                        jsonCodec,
                        frameCodec,
                        authenticationService,
                        sessionRegistry
                );
        ProxyControlServer server = new ProxyControlServer(
                sslContext,
                config.bindAddress(),
                config.authenticationTimeout(),
                authenticator,
                sessionRegistry,
                new RejectUnexpectedControlMessageHandler(frameCodec),
                nonNullLogger
        );

        return new BackendControlRuntime(
                config,
                authenticationService,
                sessionRegistry,
                secretProvider,
                server,
                nonNullLogger
        );
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Backend control runtime is already closed"
            );
        }
        if (started) {
            throw new IllegalStateException(
                    "Backend control runtime is already started"
            );
        }

        if (!config.enabled()) {
            logger.info(
                    "Backend control channel desactivado en {}.",
                    BackendControlConfigLoader.FILE_NAME
            );
            started = true;
            return;
        }

        try {
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not start backend control TLS listener",
                    exception
            );
        }

        started = true;
    }

    public synchronized void stop() {
        if (closed) {
            return;
        }

        if (server != null) {
            server.stop();
        }
        if (authenticationService != null) {
            authenticationService.clear();
        }
        if (sessionRegistry != null) {
            sessionRegistry.clear();
        }
        if (secretProvider != null) {
            secretProvider.close();
        }

        started = false;
        closed = true;
    }

    @Override
    public void close() {
        stop();
    }

    public boolean enabled() {
        return config.enabled();
    }

    public synchronized boolean started() {
        return started;
    }
}

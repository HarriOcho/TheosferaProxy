package com.theosfera.proxy.backend;

import org.slf4j.Logger;

import java.util.Objects;

public final class BackendHealthCheckTask implements Runnable {

    private final BackendAuthorizationPolicy authorizationPolicy;
    private final BackendPingEmitter pingEmitter;
    private final Logger logger;

    public BackendHealthCheckTask(
            BackendAuthorizationPolicy authorizationPolicy,
            BackendPingEmitter pingEmitter,
            Logger logger
    ) {
        this.authorizationPolicy = Objects.requireNonNull(
                authorizationPolicy,
                "authorizationPolicy cannot be null"
        );
        this.pingEmitter = Objects.requireNonNull(
                pingEmitter,
                "pingEmitter cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    @Override
    public void run() {
        for (String serverName
                : authorizationPolicy.allowedBackends().keySet()) {
            try {
                pingEmitter.emit(serverName);
            } catch (RuntimeException exception) {
                logger.warn(
                        "Health check de backend fallo para {}.",
                        serverName,
                        exception
                );
            }
        }
    }
}

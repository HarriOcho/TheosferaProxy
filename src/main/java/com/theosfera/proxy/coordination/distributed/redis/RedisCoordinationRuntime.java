package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.CoordinationStateRegistry;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLifecycle;
import com.theosfera.proxy.coordination.ProxyMembershipRenewalScheduler;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RedisCoordinationRuntime {

    private final RedisCoordinationConfig config;
    private final ProxyInstanceIdentity identity;
    private final ProxyMembershipRenewalScheduler scheduler;
    private final CoordinationStateRegistry stateRegistry;
    private final Clock clock;
    private final Logger logger;
    private final Object lock = new Object();

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private ProxyMembershipLifecycle membershipLifecycle;
    private boolean started;
    private boolean stopping;

    public RedisCoordinationRuntime(
            RedisCoordinationConfig config,
            ProxyInstanceIdentity identity,
            ProxyMembershipRenewalScheduler scheduler,
            CoordinationStateRegistry stateRegistry,
            Clock clock,
            Logger logger
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.identity = Objects.requireNonNull(identity, "identity cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.stateRegistry = Objects.requireNonNull(stateRegistry, "stateRegistry cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    public CompletionStage<Boolean> start() {
        synchronized (lock) {
            if (started || stopping) {
                throw new IllegalStateException("Redis coordination runtime cannot be started again");
            }
            started = true;
        }

        final RedisClient createdClient;
        final StatefulRedisConnection<String, String> createdConnection;
        try {
            createdClient = RedisClient.create(config.redisUri());
            createdConnection = createdClient.connect();
        } catch (RuntimeException exception) {
            synchronized (lock) {
                started = false;
            }
            stateRegistry.set(CoordinationState.FENCED);
            logger.error("No se pudo conectar con Redis para coordinacion.", exception);
            return CompletableFuture.completedFuture(false);
        }

        ProxyMembershipLifecycle lifecycle = new ProxyMembershipLifecycle(
                new RedisProxyMembershipCoordinator(createdConnection.async(), config.membershipTtl()),
                scheduler,
                stateRegistry,
                clock,
                config.membershipTtl(),
                config.membershipRenewInterval()
        );

        synchronized (lock) {
            client = createdClient;
            connection = createdConnection;
            membershipLifecycle = lifecycle;
        }

        return lifecycle.start(identity).handleAsync((acquired, failure) -> {
            if (failure != null) {
                logger.error("Fallo al adquirir la membresia distribuida del Proxy.", failure);
                closeResources();
                return false;
            }
            if (!Boolean.TRUE.equals(acquired)) {
                logger.error("No se pudo adquirir la membresia distribuida para {}.", identity.proxyName());
                closeResources();
                return false;
            }
            logger.info(
                    "Membresia distribuida adquirida para {} (incarnationId={}).",
                    identity.proxyName(),
                    identity.incarnationId()
            );
            return true;
        });
    }

    public CompletionStage<Boolean> stop() {
        final ProxyMembershipLifecycle lifecycle;
        synchronized (lock) {
            if (stopping) {
                return CompletableFuture.completedFuture(true);
            }
            stopping = true;
            lifecycle = membershipLifecycle;
        }

        CompletionStage<Boolean> releaseStage = lifecycle == null
                ? CompletableFuture.completedFuture(true)
                : lifecycle.stop();

        return releaseStage.handleAsync((released, failure) -> {
            if (failure != null) {
                logger.warn("No se pudo liberar limpiamente la membresia distribuida del Proxy.", failure);
            } else if (!Boolean.TRUE.equals(released)) {
                logger.warn("La membresia distribuida ya no pertenecia a esta incarnation al apagar.");
            }
            closeResources();
            return failure == null && Boolean.TRUE.equals(released);
        });
    }

    public RedisPlayerSessionCoordinator createPlayerSessionCoordinator(
            AuthenticatedPlayerSessionRegistry sessionRegistry
    ) {
        Objects.requireNonNull(sessionRegistry, "sessionRegistry cannot be null");

        synchronized (lock) {
            requireHealthyRuntime("Redis player session coordinator requires a healthy runtime");
            return new RedisPlayerSessionCoordinator(
                    connection.async(),
                    sessionRegistry,
                    config.playerSessionTtl()
            );
        }
    }

    public RedisPlayerPresenceCoordinator createPlayerPresenceCoordinator() {
        synchronized (lock) {
            requireHealthyRuntime("Redis player presence coordinator requires a healthy runtime");
            return new RedisPlayerPresenceCoordinator(
                    connection.async(),
                    config.playerSessionTtl()
            );
        }
    }

    public RedisCoordinationConfig config() {
        return config;
    }

    public ProxyMembershipLifecycle membershipLifecycle() {
        synchronized (lock) {
            return membershipLifecycle;
        }
    }

    private void requireHealthyRuntime(String message) {
        if (!started
                || stopping
                || connection == null
                || !stateRegistry.is(CoordinationState.HEALTHY)) {
            throw new IllegalStateException(message);
        }
    }

    private void closeResources() {
        StatefulRedisConnection<String, String> connectionToClose;
        RedisClient clientToClose;

        synchronized (lock) {
            connectionToClose = connection;
            clientToClose = client;
            connection = null;
            client = null;
            membershipLifecycle = null;
        }

        if (connectionToClose != null) {
            try {
                connectionToClose.close();
            } catch (RuntimeException exception) {
                logger.warn("Fallo al cerrar la conexion Redis.", exception);
            }
        }

        if (clientToClose != null) {
            try {
                clientToClose.shutdown();
            } catch (RuntimeException exception) {
                logger.warn("Fallo al apagar el cliente Redis.", exception);
            }
        }
    }
}

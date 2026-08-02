package com.theosfera.proxy.coordination.velocity;

import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.CoordinationStateRegistry;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.distributed.redis.RedisCoordinationConfig;
import com.theosfera.proxy.coordination.distributed.redis.RedisCoordinationConfigLoader;
import com.theosfera.proxy.coordination.distributed.redis.RedisCoordinationRuntime;
import com.theosfera.proxy.coordination.distributed.redis.RedisPlayerSessionCoordinator;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class VelocityRedisCoordinationBootstrap {

    private final ProxyServer proxyServer;
    private final Object plugin;
    private final Path dataDirectory;
    private final ProxyInstanceIdentity identity;
    private final Logger logger;
    private final Clock clock;
    private final CoordinationStateRegistry stateRegistry;
    private final VelocityCoordinationAdmissionListener admissionListener;

    private RedisCoordinationRuntime runtime;
    private boolean admissionRegistered;

    public VelocityRedisCoordinationBootstrap(
            ProxyServer proxyServer,
            Object plugin,
            Path dataDirectory,
            ProxyInstanceIdentity identity,
            Logger logger,
            Clock clock
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.dataDirectory = Objects.requireNonNull(
                dataDirectory,
                "dataDirectory cannot be null"
        );
        this.identity = Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.stateRegistry = new CoordinationStateRegistry();
        this.admissionListener = new VelocityCoordinationAdmissionListener(
                proxyServer,
                stateRegistry,
                logger
        );
    }

    public CompletionStage<Boolean> start() {
        if (runtime != null || admissionRegistered) {
            throw new IllegalStateException(
                    "Redis coordination bootstrap cannot be started again"
            );
        }

        stateRegistry.addListener(admissionListener);
        proxyServer.getEventManager().register(plugin, admissionListener);
        admissionRegistered = true;

        final RedisCoordinationConfig config;
        try {
            config = new RedisCoordinationConfigLoader(dataDirectory).load();
        } catch (RuntimeException exception) {
            stateRegistry.set(CoordinationState.FENCED);
            return CompletableFuture.failedFuture(exception);
        }

        RedisCoordinationRuntime createdRuntime = new RedisCoordinationRuntime(
                config,
                identity,
                new VelocityProxyMembershipRenewalScheduler(
                        proxyServer,
                        plugin
                ),
                stateRegistry,
                clock,
                logger
        );
        runtime = createdRuntime;

        return createdRuntime.start();
    }

    public RedisPlayerSessionCoordinator createPlayerSessionCoordinator(
            AuthenticatedPlayerSessionRegistry sessionRegistry
    ) {
        RedisCoordinationRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            throw new IllegalStateException(
                    "Redis coordination bootstrap is not running"
            );
        }

        return currentRuntime.createPlayerSessionCoordinator(
                sessionRegistry
        );
    }

    public RedisCoordinationConfig config() {
        RedisCoordinationRuntime currentRuntime = runtime;
        if (currentRuntime == null) {
            throw new IllegalStateException(
                    "Redis coordination bootstrap is not running"
            );
        }
        return currentRuntime.config();
    }

    public void beginStopping() {
        if (admissionRegistered) {
            stateRegistry.set(CoordinationState.STOPPING);
        }
    }

    public CompletionStage<Boolean> stop() {
        beginStopping();

        RedisCoordinationRuntime currentRuntime = runtime;
        CompletionStage<Boolean> stopStage = currentRuntime == null
                ? CompletableFuture.completedFuture(true)
                : currentRuntime.stop();

        return stopStage.handle((released, failure) -> {
            unregisterAdmission();
            runtime = null;

            if (failure != null) {
                throw new RuntimeException(
                        "Could not stop Redis coordination runtime",
                        failure
                );
            }
            return Boolean.TRUE.equals(released);
        });
    }

    public CoordinationState state() {
        return stateRegistry.get();
    }

    private void unregisterAdmission() {
        if (!admissionRegistered) {
            return;
        }

        proxyServer.getEventManager().unregisterListener(
                plugin,
                admissionListener
        );
        stateRegistry.removeListener(admissionListener);
        admissionRegistered = false;
    }
}

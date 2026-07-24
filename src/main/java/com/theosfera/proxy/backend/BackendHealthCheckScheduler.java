package com.theosfera.proxy.backend;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;

public final class BackendHealthCheckScheduler {

    public static final Duration DEFAULT_INTERVAL =
            Duration.ofSeconds(5);

    private final ProxyServer proxyServer;
    private final Object plugin;
    private final Runnable task;
    private final Duration interval;
    private final Logger logger;

    private ScheduledTask scheduledTask;

    public BackendHealthCheckScheduler(
            ProxyServer proxyServer,
            Object plugin,
            Runnable task,
            Logger logger
    ) {
        this(
                proxyServer,
                plugin,
                task,
                DEFAULT_INTERVAL,
                logger
        );
    }

    BackendHealthCheckScheduler(
            ProxyServer proxyServer,
            Object plugin,
            Runnable task,
            Duration interval,
            Logger logger
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin cannot be null"
        );
        this.task = Objects.requireNonNull(
                task,
                "task cannot be null"
        );
        this.interval = requirePositiveInterval(interval);
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public void start() {
        if (scheduledTask != null) {
            return;
        }

        scheduledTask = proxyServer
                .getScheduler()
                .buildTask(plugin, this::runSafely)
                .repeat(interval)
                .schedule();
    }

    public void stop() {
        if (scheduledTask == null) {
            return;
        }

        scheduledTask.cancel();
        scheduledTask = null;
    }

    private void runSafely() {
        try {
            task.run();
        } catch (RuntimeException exception) {
            logger.warn(
                    "La tarea periodica de health checking fallo.",
                    exception
            );
        }
    }

    private static Duration requirePositiveInterval(
            Duration interval
    ) {
        Duration nonNullInterval = Objects.requireNonNull(
                interval,
                "interval cannot be null"
        );

        if (nonNullInterval.isZero()
                || nonNullInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "interval must be positive"
            );
        }

        return nonNullInterval;
    }
}

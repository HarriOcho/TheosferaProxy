package com.theosfera.proxy.orchestration.velocity;

import com.theosfera.proxy.orchestration.BackendReadinessScheduler;
import com.theosfera.proxy.orchestration.BackendStartupScheduler;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.time.Duration;
import java.util.Objects;

/**
 * One-shot Velocity scheduler shared by backend startup retries/timeouts and
 * readiness polling/timeouts.
 */
public final class VelocityBackendOrchestrationScheduler
        implements BackendStartupScheduler, BackendReadinessScheduler {

    private final ProxyServer proxyServer;
    private final Object plugin;

    public VelocityBackendOrchestrationScheduler(
            ProxyServer proxyServer,
            Object plugin
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin cannot be null"
        );
    }

    @Override
    public Handle schedule(Runnable task, Duration delay) {
        Runnable nonNullTask = Objects.requireNonNull(
                task,
                "task cannot be null"
        );
        Duration nonNullDelay = requirePositive(delay);

        ScheduledTask scheduledTask = proxyServer
                .getScheduler()
                .buildTask(plugin, nonNullTask)
                .delay(nonNullDelay)
                .schedule();

        if (scheduledTask == null) {
            throw new IllegalStateException(
                    "Velocity scheduler returned null backend orchestration task"
            );
        }

        return scheduledTask::cancel;
    }

    private static Duration requirePositive(Duration delay) {
        Duration nonNullDelay = Objects.requireNonNull(
                delay,
                "delay cannot be null"
        );
        if (nonNullDelay.isZero() || nonNullDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "delay must be positive"
            );
        }
        return nonNullDelay;
    }
}

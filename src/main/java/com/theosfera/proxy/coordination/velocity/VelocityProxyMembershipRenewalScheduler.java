package com.theosfera.proxy.coordination.velocity;

import com.theosfera.proxy.coordination.ProxyMembershipRenewalScheduler;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.time.Duration;
import java.util.Objects;

public final class VelocityProxyMembershipRenewalScheduler
        implements ProxyMembershipRenewalScheduler {

    private final ProxyServer proxyServer;
    private final Object plugin;

    public VelocityProxyMembershipRenewalScheduler(
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
    public Handle schedule(
            Runnable task,
            Duration interval
    ) {
        Runnable nonNullTask = Objects.requireNonNull(
                task,
                "task cannot be null"
        );
        Duration nonNullInterval = requirePositive(interval);

        ScheduledTask scheduledTask = proxyServer
                .getScheduler()
                .buildTask(plugin, nonNullTask)
                .repeat(nonNullInterval)
                .schedule();

        if (scheduledTask == null) {
            throw new IllegalStateException(
                    "Velocity scheduler returned null membership task"
            );
        }

        return scheduledTask::cancel;
    }

    private static Duration requirePositive(Duration interval) {
        Duration nonNullInterval = Objects.requireNonNull(
                interval,
                "interval cannot be null"
        );

        if (nonNullInterval.isZero() || nonNullInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "interval must be positive"
            );
        }

        return nonNullInterval;
    }
}

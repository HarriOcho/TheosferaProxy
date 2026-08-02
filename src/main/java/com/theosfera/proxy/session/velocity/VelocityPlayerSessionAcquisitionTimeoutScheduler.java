package com.theosfera.proxy.session.velocity;

import com.theosfera.proxy.session.PlayerSessionAcquisitionTimeoutScheduler;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.time.Duration;
import java.util.Objects;

public final class VelocityPlayerSessionAcquisitionTimeoutScheduler
        implements PlayerSessionAcquisitionTimeoutScheduler {

    public static final Duration DEFAULT_TIMEOUT =
            Duration.ofSeconds(10);

    private final ProxyServer proxyServer;
    private final Object plugin;
    private final Duration timeout;

    public VelocityPlayerSessionAcquisitionTimeoutScheduler(
            ProxyServer proxyServer,
            Object plugin
    ) {
        this(
                proxyServer,
                plugin,
                DEFAULT_TIMEOUT
        );
    }

    public VelocityPlayerSessionAcquisitionTimeoutScheduler(
            ProxyServer proxyServer,
            Object plugin,
            Duration timeout
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );

        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin cannot be null"
        );

        this.timeout = requirePositiveTimeout(timeout);
    }

    @Override
    public ScheduledAcquisitionTimeout schedule(
            AcquisitionTimeoutKey key,
            Runnable timeoutRunnable
    ) {
        Objects.requireNonNull(
                key,
                "key cannot be null"
        );

        Runnable nonNullTimeoutRunnable =
                Objects.requireNonNull(
                        timeoutRunnable,
                        "timeoutRunnable cannot be null"
                );

        ScheduledTask scheduledTask =
                proxyServer
                        .getScheduler()
                        .buildTask(
                                plugin,
                                nonNullTimeoutRunnable
                        )
                        .delay(timeout)
                        .schedule();

        return scheduledTask::cancel;
    }

    private static Duration requirePositiveTimeout(
            Duration timeout
    ) {
        Duration nonNullTimeout =
                Objects.requireNonNull(
                        timeout,
                        "timeout cannot be null"
                );

        if (nonNullTimeout.isZero()
                || nonNullTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "timeout must be positive"
            );
        }

        return nonNullTimeout;
    }
}

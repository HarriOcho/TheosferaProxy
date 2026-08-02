package com.theosfera.proxy.session.velocity;

import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.PlayerSessionReleaseTimeoutScheduler;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityPlayerSessionReleaseTimeoutSchedulerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final ProxyInstanceIdentity PROXY_IDENTITY =
            new ProxyInstanceIdentity(
                    "proxy-release-timeout-test",
                    UUID.fromString(
                            "09989199-f70d-4a0a-b442-0efd5aed14ef"
                    )
            );

    @Test
    void schedulesDelayedTimeoutAndCancelsExactVelocityTask() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Scheduler velocityScheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder =
                mock(Scheduler.TaskBuilder.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        Object plugin = new Object();
        Duration timeout = Duration.ofSeconds(3);
        Runnable timeoutRunnable = mock(Runnable.class);

        when(proxyServer.getScheduler())
                .thenReturn(velocityScheduler);
        when(velocityScheduler.buildTask(
                plugin,
                timeoutRunnable
        )).thenReturn(taskBuilder);
        when(taskBuilder.delay(timeout))
                .thenReturn(taskBuilder);
        when(taskBuilder.schedule())
                .thenReturn(scheduledTask);

        VelocityPlayerSessionReleaseTimeoutScheduler scheduler =
                new VelocityPlayerSessionReleaseTimeoutScheduler(
                        proxyServer,
                        plugin,
                        timeout
                );

        PlayerSessionReleaseTimeoutScheduler
                .ScheduledReleaseTimeout scheduledTimeout =
                scheduler.schedule(
                        key(),
                        timeoutRunnable
                );

        verify(proxyServer, times(1)).getScheduler();
        verify(velocityScheduler, times(1)).buildTask(
                plugin,
                timeoutRunnable
        );
        verify(taskBuilder, times(1)).delay(timeout);
        verify(taskBuilder, times(1)).schedule();

        scheduledTimeout.cancel();

        verify(scheduledTask, times(1)).cancel();
    }

    @Test
    void usesDefaultTenSecondTimeout() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Scheduler velocityScheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder =
                mock(Scheduler.TaskBuilder.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        Object plugin = new Object();
        Runnable timeoutRunnable = mock(Runnable.class);

        when(proxyServer.getScheduler())
                .thenReturn(velocityScheduler);
        when(velocityScheduler.buildTask(
                plugin,
                timeoutRunnable
        )).thenReturn(taskBuilder);
        when(taskBuilder.delay(
                VelocityPlayerSessionReleaseTimeoutScheduler
                        .DEFAULT_TIMEOUT
        )).thenReturn(taskBuilder);
        when(taskBuilder.schedule())
                .thenReturn(scheduledTask);

        VelocityPlayerSessionReleaseTimeoutScheduler scheduler =
                new VelocityPlayerSessionReleaseTimeoutScheduler(
                        proxyServer,
                        plugin
                );

        scheduler.schedule(
                key(),
                timeoutRunnable
        );

        assertEquals(
                Duration.ofSeconds(10),
                VelocityPlayerSessionReleaseTimeoutScheduler
                        .DEFAULT_TIMEOUT
        );

        verify(taskBuilder, times(1)).delay(
                VelocityPlayerSessionReleaseTimeoutScheduler
                        .DEFAULT_TIMEOUT
        );
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Object plugin = new Object();

        assertThrows(
                NullPointerException.class,
                () -> new VelocityPlayerSessionReleaseTimeoutScheduler(
                        null,
                        plugin,
                        Duration.ofSeconds(3)
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new VelocityPlayerSessionReleaseTimeoutScheduler(
                        proxyServer,
                        null,
                        Duration.ofSeconds(3)
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new VelocityPlayerSessionReleaseTimeoutScheduler(
                        proxyServer,
                        plugin,
                        null
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new VelocityPlayerSessionReleaseTimeoutScheduler(
                        proxyServer,
                        plugin,
                        Duration.ZERO
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new VelocityPlayerSessionReleaseTimeoutScheduler(
                        proxyServer,
                        plugin,
                        Duration.ofSeconds(-1)
                )
        );
    }

    @Test
    void rejectsNullScheduleArguments() {
        VelocityPlayerSessionReleaseTimeoutScheduler scheduler =
                new VelocityPlayerSessionReleaseTimeoutScheduler(
                        mock(ProxyServer.class),
                        new Object(),
                        Duration.ofSeconds(3)
                );

        assertThrows(
                NullPointerException.class,
                () -> scheduler.schedule(
                        null,
                        mock(Runnable.class)
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> scheduler.schedule(
                        key(),
                        null
                )
        );
    }

    private PlayerSessionReleaseTimeoutScheduler
    .ReleaseTimeoutKey key() {
        PlayerSessionLease lease =
                new PlayerSessionLease(
                        new AuthenticatedPlayerSession(
                                PLAYER_ID,
                                "HarriOcho",
                                1_000L
                        ),
                        PROXY_IDENTITY,
                        1L
                );

        return new PlayerSessionReleaseTimeoutScheduler
                .ReleaseTimeoutKey(
                PLAYER_ID,
                lease,
                lease.fencingToken(),
                new CompletableFuture<>()
        );
    }
}

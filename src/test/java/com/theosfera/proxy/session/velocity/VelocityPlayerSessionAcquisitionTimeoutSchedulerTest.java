package com.theosfera.proxy.session.velocity;

import com.theosfera.proxy.session.PlayerSessionAcquisitionTimeoutScheduler;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityPlayerSessionAcquisitionTimeoutSchedulerTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    private static final UUID REQUEST_ID =
            UUID.fromString(
                    "11111111-2222-3333-4444-555555555555"
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

        VelocityPlayerSessionAcquisitionTimeoutScheduler
                scheduler =
                new VelocityPlayerSessionAcquisitionTimeoutScheduler(
                        proxyServer,
                        plugin,
                        timeout
                );

        PlayerSessionAcquisitionTimeoutScheduler
                .ScheduledAcquisitionTimeout scheduledTimeout =
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
                VelocityPlayerSessionAcquisitionTimeoutScheduler
                        .DEFAULT_TIMEOUT
        )).thenReturn(taskBuilder);
        when(taskBuilder.schedule())
                .thenReturn(scheduledTask);

        VelocityPlayerSessionAcquisitionTimeoutScheduler
                scheduler =
                new VelocityPlayerSessionAcquisitionTimeoutScheduler(
                        proxyServer,
                        plugin
                );

        scheduler.schedule(
                key(),
                timeoutRunnable
        );

        assertEquals(
                Duration.ofSeconds(10),
                VelocityPlayerSessionAcquisitionTimeoutScheduler
                        .DEFAULT_TIMEOUT
        );

        verify(taskBuilder, times(1)).delay(
                VelocityPlayerSessionAcquisitionTimeoutScheduler
                        .DEFAULT_TIMEOUT
        );
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Object plugin = new Object();

        assertThrows(
                NullPointerException.class,
                () -> new VelocityPlayerSessionAcquisitionTimeoutScheduler(
                        null,
                        plugin,
                        Duration.ofSeconds(3)
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new VelocityPlayerSessionAcquisitionTimeoutScheduler(
                        proxyServer,
                        null,
                        Duration.ofSeconds(3)
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new VelocityPlayerSessionAcquisitionTimeoutScheduler(
                        proxyServer,
                        plugin,
                        null
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new VelocityPlayerSessionAcquisitionTimeoutScheduler(
                        proxyServer,
                        plugin,
                        Duration.ZERO
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new VelocityPlayerSessionAcquisitionTimeoutScheduler(
                        proxyServer,
                        plugin,
                        Duration.ofSeconds(-1)
                )
        );
    }

    @Test
    void rejectsNullScheduleArguments() {
        VelocityPlayerSessionAcquisitionTimeoutScheduler
                scheduler =
                new VelocityPlayerSessionAcquisitionTimeoutScheduler(
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

    private PlayerSessionAcquisitionTimeoutScheduler
    .AcquisitionTimeoutKey key() {
        return new PlayerSessionAcquisitionTimeoutScheduler
                .AcquisitionTimeoutKey(
                PLAYER_ID,
                REQUEST_ID,
                1L
        );
    }
}

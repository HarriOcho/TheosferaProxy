package com.theosfera.proxy.coordination.velocity;

import com.theosfera.proxy.coordination.BackendBootstrapRenewalScheduler;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityBackendBootstrapRenewalSchedulerTest {

    @Test
    void schedulesRepeatingTaskAndCancelsVelocityTask() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        Object plugin = new Object();
        Runnable task = () -> { };
        Duration interval = Duration.ofSeconds(20);

        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), same(task))).thenReturn(builder);
        when(builder.repeat(interval)).thenReturn(builder);
        when(builder.schedule()).thenReturn(scheduledTask);

        VelocityBackendBootstrapRenewalScheduler renewalScheduler =
                new VelocityBackendBootstrapRenewalScheduler(
                        proxyServer,
                        plugin
                );

        BackendBootstrapRenewalScheduler.Handle handle =
                renewalScheduler.schedule(task, interval);
        handle.cancel();

        verify(builder).repeat(interval);
        verify(builder).schedule();
        verify(scheduledTask).cancel();
    }

    @Test
    void rejectsNonPositiveIntervalBeforeScheduling() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Scheduler scheduler = mock(Scheduler.class);
        Object plugin = new Object();
        Runnable task = () -> { };

        when(proxyServer.getScheduler()).thenReturn(scheduler);

        VelocityBackendBootstrapRenewalScheduler renewalScheduler =
                new VelocityBackendBootstrapRenewalScheduler(
                        proxyServer,
                        plugin
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> renewalScheduler.schedule(task, Duration.ZERO)
        );
        verify(scheduler, never()).buildTask(eq(plugin), same(task));
    }

    @Test
    void nullVelocityScheduledTaskFailsClosed() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class);
        Object plugin = new Object();
        Runnable task = () -> { };
        Duration interval = Duration.ofSeconds(20);

        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), same(task))).thenReturn(builder);
        when(builder.repeat(interval)).thenReturn(builder);
        when(builder.schedule()).thenReturn(null);

        VelocityBackendBootstrapRenewalScheduler renewalScheduler =
                new VelocityBackendBootstrapRenewalScheduler(
                        proxyServer,
                        plugin
                );

        assertThrows(
                IllegalStateException.class,
                () -> renewalScheduler.schedule(task, interval)
        );
    }
}

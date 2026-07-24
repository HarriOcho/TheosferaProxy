package com.theosfera.proxy.backend;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendHealthCheckSchedulerTest {

    private ProxyServer proxyServer;
    private Scheduler velocityScheduler;
    private Scheduler.TaskBuilder taskBuilder;
    private ScheduledTask scheduledTask;
    private Runnable task;
    private Logger logger;
    private Object plugin;
    private BackendHealthCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        proxyServer = mock(ProxyServer.class);
        velocityScheduler = mock(Scheduler.class);
        taskBuilder = mock(Scheduler.TaskBuilder.class);
        scheduledTask = mock(ScheduledTask.class);
        task = mock(Runnable.class);
        logger = mock(Logger.class);
        plugin = new Object();

        when(proxyServer.getScheduler())
                .thenReturn(velocityScheduler);
        when(velocityScheduler.buildTask(
                org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.any(Runnable.class)
        )).thenReturn(taskBuilder);
        when(taskBuilder.repeat(Duration.ofSeconds(5)))
                .thenReturn(taskBuilder);
        when(taskBuilder.schedule())
                .thenReturn(scheduledTask);

        scheduler = new BackendHealthCheckScheduler(
                proxyServer,
                plugin,
                task,
                logger
        );
    }

    @Test
    void startSchedulesOnePeriodicTask() {
        scheduler.start();

        verify(proxyServer).getScheduler();
        verify(velocityScheduler).buildTask(
                org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.any(Runnable.class)
        );
        verify(taskBuilder).repeat(Duration.ofSeconds(5));
        verify(taskBuilder).schedule();
    }

    @Test
    void startTwiceDoesNotDuplicateTask() {
        scheduler.start();
        scheduler.start();

        verify(velocityScheduler, times(1)).buildTask(
                org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.any(Runnable.class)
        );
        verify(taskBuilder, times(1)).schedule();
    }

    @Test
    void stopCancelsScheduledTask() {
        scheduler.start();

        scheduler.stop();

        verify(scheduledTask).cancel();
    }

    @Test
    void repeatedStopIsSafe() {
        scheduler.stop();
        scheduler.start();
        scheduler.stop();
        scheduler.stop();

        verify(scheduledTask).cancel();
    }

    @Test
    void scheduledRunnableContainsTaskException() {
        RuntimeException exception =
                new RuntimeException("boom");
        ArgumentCaptor<Runnable> runnableCaptor =
                ArgumentCaptor.forClass(Runnable.class);

        org.mockito.Mockito.doThrow(exception)
                .when(task)
                .run();

        scheduler.start();

        verify(velocityScheduler).buildTask(
                org.mockito.ArgumentMatchers.eq(plugin),
                runnableCaptor.capture()
        );

        runnableCaptor.getValue().run();

        verify(logger).warn(
                "La tarea periodica de health checking fallo.",
                exception
        );
    }

    @Test
    void stopBeforeStartDoesNotCancelAnything() {
        scheduler.stop();

        verify(scheduledTask, never()).cancel();
    }

    @Test
    void usesDefaultInterval() {
        assertEquals(
                Duration.ofSeconds(5),
                BackendHealthCheckScheduler.DEFAULT_INTERVAL
        );
    }
}

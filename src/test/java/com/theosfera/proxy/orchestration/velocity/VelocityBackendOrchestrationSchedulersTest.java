package com.theosfera.proxy.orchestration.velocity;

import com.theosfera.proxy.orchestration.BackendReadinessScheduler;
import com.theosfera.proxy.orchestration.BackendStartupScheduler;
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

class VelocityBackendOrchestrationSchedulersTest {

    @Test
    void startupSchedulerUsesOneShotVelocityDelayAndCancellation() {
        Fixture fixture = new Fixture();
        Duration delay = Duration.ofSeconds(2);

        BackendStartupScheduler.Handle handle =
                new VelocityBackendStartupScheduler(
                        fixture.proxyServer,
                        fixture.plugin
                ).schedule(fixture.task, delay);
        handle.cancel();

        verify(fixture.builder).delay(delay);
        verify(fixture.builder).schedule();
        verify(fixture.scheduledTask).cancel();
    }

    @Test
    void readinessSchedulerUsesOneShotVelocityDelayAndCancellation() {
        Fixture fixture = new Fixture();
        Duration delay = Duration.ofSeconds(1);

        BackendReadinessScheduler.Handle handle =
                new VelocityBackendReadinessScheduler(
                        fixture.proxyServer,
                        fixture.plugin
                ).schedule(fixture.task, delay);
        handle.cancel();

        verify(fixture.builder).delay(delay);
        verify(fixture.builder).schedule();
        verify(fixture.scheduledTask).cancel();
    }

    @Test
    void startupSchedulerRejectsNonPositiveDelay() {
        Fixture fixture = new Fixture(false);
        VelocityBackendStartupScheduler scheduler =
                new VelocityBackendStartupScheduler(
                        fixture.proxyServer,
                        fixture.plugin
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(fixture.task, Duration.ZERO)
        );
        verify(fixture.scheduler, never()).buildTask(
                eq(fixture.plugin),
                same(fixture.task)
        );
    }

    @Test
    void readinessSchedulerRejectsNonPositiveDelay() {
        Fixture fixture = new Fixture(false);
        VelocityBackendReadinessScheduler scheduler =
                new VelocityBackendReadinessScheduler(
                        fixture.proxyServer,
                        fixture.plugin
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(fixture.task, Duration.ZERO)
        );
        verify(fixture.scheduler, never()).buildTask(
                eq(fixture.plugin),
                same(fixture.task)
        );
    }

    @Test
    void nullScheduledTaskFailsClosed() {
        Fixture fixture = new Fixture(false);
        when(fixture.scheduler.buildTask(
                eq(fixture.plugin),
                same(fixture.task)
        )).thenReturn(fixture.builder);
        when(fixture.builder.delay(Duration.ofSeconds(1)))
                .thenReturn(fixture.builder);
        when(fixture.builder.schedule()).thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> new VelocityBackendReadinessScheduler(
                        fixture.proxyServer,
                        fixture.plugin
                ).schedule(fixture.task, Duration.ofSeconds(1))
        );
    }

    private static final class Fixture {
        private final ProxyServer proxyServer = mock(ProxyServer.class);
        private final Scheduler scheduler = mock(Scheduler.class);
        private final Scheduler.TaskBuilder builder = mock(
                Scheduler.TaskBuilder.class
        );
        private final ScheduledTask scheduledTask = mock(ScheduledTask.class);
        private final Object plugin = new Object();
        private final Runnable task = () -> { };

        private Fixture() {
            this(true);
        }

        private Fixture(boolean configureSuccess) {
            when(proxyServer.getScheduler()).thenReturn(scheduler);
            if (configureSuccess) {
                when(scheduler.buildTask(eq(plugin), same(task)))
                        .thenReturn(builder);
                when(builder.delay(Duration.ofSeconds(1))).thenReturn(builder);
                when(builder.delay(Duration.ofSeconds(2))).thenReturn(builder);
                when(builder.schedule()).thenReturn(scheduledTask);
            }
        }
    }
}

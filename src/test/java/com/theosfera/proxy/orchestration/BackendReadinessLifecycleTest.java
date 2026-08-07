package com.theosfera.proxy.orchestration;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipLifecycle;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipState;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendReadinessLifecycleTest {

    private static final BackendReadinessPolicy POLICY =
            new BackendReadinessPolicy(
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(1)
            );

    @Test
    void currentControlIdentityAndHealthyEvidenceCompleteReady() {
        OwnershipContext ownership = ownedContext();
        BackendReadinessProbe probe = mock(BackendReadinessProbe.class);
        when(probe.check("lobby-2")).thenReturn(ready());
        ManualScheduler scheduler = new ManualScheduler();

        BackendReadinessLifecycle lifecycle = lifecycle(
                ownership.lifecycle(),
                probe,
                scheduler
        );

        assertEquals(
                BackendReadinessLifecycleState.READY,
                lifecycle.start().toCompletableFuture().join()
        );
        assertEquals(BackendReadinessLifecycleState.READY, lifecycle.state());
        verify(ownership.lifecycle(), never()).stop();
        assertEquals(0, scheduler.activeTaskCount());
    }

    @Test
    void waitsForControlThenFreshHealthBeforeReady() {
        OwnershipContext ownership = ownedContext();
        BackendReadinessProbe probe = mock(BackendReadinessProbe.class);
        when(probe.check("lobby-2")).thenReturn(
                snapshot(BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED),
                snapshot(BackendReadinessStatus.HEALTH_NOT_READY),
                ready()
        );
        ManualScheduler scheduler = new ManualScheduler();
        BackendReadinessLifecycle lifecycle = lifecycle(
                ownership.lifecycle(),
                probe,
                scheduler
        );

        CompletableFuture<BackendReadinessLifecycleState> completion =
                lifecycle.start().toCompletableFuture();

        assertFalse(completion.isDone());
        assertEquals(
                BackendReadinessLifecycleState.WAITING_CONTROL,
                lifecycle.state()
        );

        scheduler.run(Duration.ofSeconds(1));
        assertEquals(
                BackendReadinessLifecycleState.WAITING_HEALTH,
                lifecycle.state()
        );

        scheduler.run(Duration.ofSeconds(1));
        assertEquals(
                BackendReadinessLifecycleState.READY,
                completion.join()
        );
        verify(ownership.lifecycle(), never()).stop();
    }

    @Test
    void timeoutIsIndependentFromPollCallbacksAndCleansOwnership() {
        OwnershipContext ownership = ownedContext();
        BackendReadinessProbe probe = mock(BackendReadinessProbe.class);
        when(probe.check("lobby-2")).thenReturn(
                snapshot(BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED)
        );
        ManualScheduler scheduler = new ManualScheduler();
        BackendReadinessLifecycle lifecycle = lifecycle(
                ownership.lifecycle(),
                probe,
                scheduler
        );

        CompletableFuture<BackendReadinessLifecycleState> completion =
                lifecycle.start().toCompletableFuture();

        scheduler.run(Duration.ofSeconds(30));

        assertEquals(
                BackendReadinessLifecycleState.TIMED_OUT,
                completion.join()
        );
        verify(ownership.lifecycle()).stop();
        assertEquals(0, scheduler.activeTaskCount());
    }

    @Test
    void ownershipFencingAbortsWithoutTryingToReleaseUnknownAuthority() {
        OwnershipContext ownership = ownedContext();
        BackendReadinessProbe probe = mock(BackendReadinessProbe.class);
        when(probe.check("lobby-2")).thenReturn(
                snapshot(BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED)
        );
        ManualScheduler scheduler = new ManualScheduler();
        BackendReadinessLifecycle lifecycle = lifecycle(
                ownership.lifecycle(),
                probe,
                scheduler
        );

        CompletableFuture<BackendReadinessLifecycleState> completion =
                lifecycle.start().toCompletableFuture();

        ownership.termination().complete(
                BackendBootstrapOwnershipState.FENCED
        );

        assertEquals(
                BackendReadinessLifecycleState.FENCED,
                completion.join()
        );
        verify(ownership.lifecycle(), never()).stop();
        assertEquals(0, scheduler.activeTaskCount());
    }

    @Test
    void changedExactLeaseFencesBeforeAnotherProbe() {
        BackendBootstrapLease first = bootstrapLease(41L);
        BackendBootstrapLease replacement = bootstrapLease(42L);
        BackendBootstrapOwnershipLifecycle ownership = mock(
                BackendBootstrapOwnershipLifecycle.class
        );
        CompletableFuture<BackendBootstrapOwnershipState> termination =
                new CompletableFuture<>();
        when(ownership.termination()).thenReturn(termination);
        when(ownership.hasAuthority()).thenReturn(true);
        when(ownership.currentLease()).thenReturn(first, first, replacement);
        when(ownership.stop()).thenReturn(
                CompletableFuture.completedFuture(Optional.empty())
        );

        BackendReadinessProbe probe = mock(BackendReadinessProbe.class);
        when(probe.check("lobby-2")).thenReturn(
                snapshot(BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED)
        );
        ManualScheduler scheduler = new ManualScheduler();
        BackendReadinessLifecycle lifecycle = lifecycle(
                ownership,
                probe,
                scheduler
        );

        CompletableFuture<BackendReadinessLifecycleState> completion =
                lifecycle.start().toCompletableFuture();

        scheduler.run(Duration.ofSeconds(1));

        assertEquals(
                BackendReadinessLifecycleState.FENCED,
                completion.join()
        );
        verify(ownership, never()).stop();
    }

    @Test
    void identityMismatchFailsClosedAndReleasesOwnedBootstrap() {
        OwnershipContext ownership = ownedContext();
        BackendReadinessProbe probe = mock(BackendReadinessProbe.class);
        when(probe.check("lobby-2")).thenReturn(
                snapshot(BackendReadinessStatus.IDENTITY_MISMATCH)
        );
        ManualScheduler scheduler = new ManualScheduler();
        BackendReadinessLifecycle lifecycle = lifecycle(
                ownership.lifecycle(),
                probe,
                scheduler
        );

        assertEquals(
                BackendReadinessLifecycleState.FAILED,
                lifecycle.start().toCompletableFuture().join()
        );
        verify(ownership.lifecycle()).stop();
    }

    @Test
    void cancelStopsOwnershipAndPreventsLaterPoll() {
        OwnershipContext ownership = ownedContext();
        BackendReadinessProbe probe = mock(BackendReadinessProbe.class);
        when(probe.check("lobby-2")).thenReturn(
                snapshot(BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED)
        );
        ManualScheduler scheduler = new ManualScheduler();
        BackendReadinessLifecycle lifecycle = lifecycle(
                ownership.lifecycle(),
                probe,
                scheduler
        );

        lifecycle.start();
        assertEquals(
                BackendReadinessLifecycleState.CANCELLED,
                lifecycle.cancel().toCompletableFuture().join()
        );
        verify(ownership.lifecycle()).stop();
        assertEquals(0, scheduler.activeTaskCount());
        assertFalse(scheduler.runIfPresent(Duration.ofSeconds(1)));
    }

    private static BackendReadinessLifecycle lifecycle(
            BackendBootstrapOwnershipLifecycle ownership,
            BackendReadinessProbe probe,
            BackendReadinessScheduler scheduler
    ) {
        return new BackendReadinessLifecycle(
                ownership,
                probe,
                scheduler,
                POLICY
        );
    }

    private static OwnershipContext ownedContext() {
        BackendBootstrapOwnershipLifecycle lifecycle = mock(
                BackendBootstrapOwnershipLifecycle.class
        );
        CompletableFuture<BackendBootstrapOwnershipState> termination =
                new CompletableFuture<>();
        when(lifecycle.termination()).thenReturn(termination);
        when(lifecycle.hasAuthority()).thenReturn(true);
        when(lifecycle.currentLease()).thenReturn(bootstrapLease(41L));
        when(lifecycle.stop()).thenReturn(
                CompletableFuture.completedFuture(Optional.empty())
        );
        return new OwnershipContext(lifecycle, termination);
    }

    private static BackendReadinessSnapshot ready() {
        return BackendReadinessSnapshot.ready(
                new BackendIdentity("lobby-2", BackendType.LOBBY)
        );
    }

    private static BackendReadinessSnapshot snapshot(
            BackendReadinessStatus status
    ) {
        return switch (status) {
            case CONTROL_NOT_AUTHENTICATED -> BackendReadinessSnapshot.of(
                    status,
                    null,
                    BackendHealthStatus.UNKNOWN
            );
            case HEALTH_NOT_READY -> BackendReadinessSnapshot.of(
                    status,
                    new BackendIdentity("lobby-2", BackendType.LOBBY),
                    BackendHealthStatus.UNKNOWN
            );
            case IDENTITY_MISMATCH -> BackendReadinessSnapshot.of(
                    status,
                    new BackendIdentity("lobby-2", BackendType.SKYBLOCK),
                    BackendHealthStatus.HEALTHY
            );
            default -> BackendReadinessSnapshot.of(status, null, null);
        };
    }

    private static BackendBootstrapLease bootstrapLease(long fencingToken) {
        return new BackendBootstrapLease(
                "lobby-2",
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000002"
                ),
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000003"
                ),
                new ProxyMembershipLease(
                        new ProxyInstanceIdentity(
                                "proxy-1",
                                UUID.fromString(
                                        "00000000-0000-0000-0000-000000000001"
                                )
                        ),
                        7L
                ),
                fencingToken
        );
    }

    private record OwnershipContext(
            BackendBootstrapOwnershipLifecycle lifecycle,
            CompletableFuture<BackendBootstrapOwnershipState> termination
    ) {
    }

    private static final class ManualScheduler
            implements BackendReadinessScheduler {

        private final List<Scheduled> tasks = new ArrayList<>();

        @Override
        public Handle schedule(Runnable task, Duration delay) {
            Scheduled scheduled = new Scheduled(task, delay);
            tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        int activeTaskCount() {
            return (int) tasks.stream()
                    .filter(task -> !task.cancelled && !task.executed)
                    .count();
        }

        void run(Duration delay) {
            Scheduled scheduled = tasks.stream()
                    .filter(task -> task.delay.equals(delay))
                    .filter(task -> !task.cancelled)
                    .filter(task -> !task.executed)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no active task for delay " + delay
                    ));
            scheduled.executed = true;
            scheduled.task.run();
        }

        boolean runIfPresent(Duration delay) {
            Optional<Scheduled> scheduled = tasks.stream()
                    .filter(task -> task.delay.equals(delay))
                    .filter(task -> !task.cancelled)
                    .filter(task -> !task.executed)
                    .findFirst();
            scheduled.ifPresent(task -> {
                task.executed = true;
                task.task.run();
            });
            return scheduled.isPresent();
        }

        private static final class Scheduled {
            private final Runnable task;
            private final Duration delay;
            private boolean cancelled;
            private boolean executed;

            private Scheduled(Runnable task, Duration delay) {
                this.task = task;
                this.delay = delay;
            }
        }
    }
}

package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipLifecycle;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipState;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendStartupOperationLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.EPOCH,
            ZoneOffset.UTC
    );

    private static final BackendStartupPolicy POLICY =
            new BackendStartupPolicy(
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(4)
            );

    @Test
    void acceptedStartHandsOffWithoutReleasingBootstrapOwnership() {
        OwnershipContext ownership = ownedContext();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        AtomicInteger calls = new AtomicInteger();
        BackendOrchestrationProvider provider = request -> {
            calls.incrementAndGet();
            assertEquals(
                    ownership.lease(),
                    request.bootstrapLease()
            );
            return CompletableFuture.completedFuture(
                    BackendStartResult.accepted()
            );
        };

        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                provider,
                scheduler
        );

        BackendStartupOperationState result = lifecycle.start()
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendStartupOperationState.START_ACCEPTED,
                result
        );
        assertEquals(
                BackendStartupOperationState.START_ACCEPTED,
                lifecycle.state()
        );
        assertEquals(1, calls.get());
        assertEquals(0, scheduler.activeTaskCount());
        verify(ownership.lifecycle(), never()).stop();
    }

    @Test
    void providerUnavailableRetriesThenAccepts() {
        OwnershipContext ownership = ownedContext();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        AtomicInteger calls = new AtomicInteger();
        BackendOrchestrationProvider provider = request -> {
            int call = calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    call == 1
                            ? BackendStartResult.of(
                                    BackendStartResult.Status
                                            .PROVIDER_UNAVAILABLE
                            )
                            : BackendStartResult.accepted()
            );
        };

        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                provider,
                scheduler
        );
        CompletableFuture<BackendStartupOperationState> completion =
                lifecycle.start().toCompletableFuture();

        assertFalse(completion.isDone());
        assertEquals(
                BackendStartupOperationState.RETRY_WAIT,
                lifecycle.state()
        );
        assertTrue(scheduler.hasActiveTask(Duration.ofSeconds(1)));

        scheduler.run(Duration.ofSeconds(1));

        assertEquals(
                BackendStartupOperationState.START_ACCEPTED,
                completion.join()
        );
        assertEquals(2, calls.get());
        verify(ownership.lifecycle(), never()).stop();
    }

    @Test
    void repeatedProviderUnavailabilityUsesCappedExponentialBackoff() {
        OwnershipContext ownership = ownedContext();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        AtomicInteger calls = new AtomicInteger();
        BackendOrchestrationProvider provider = request -> {
            int call = calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    call < 4
                            ? BackendStartResult.of(
                                    BackendStartResult.Status
                                            .PROVIDER_UNAVAILABLE
                            )
                            : BackendStartResult.accepted()
            );
        };

        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                provider,
                scheduler
        );
        CompletableFuture<BackendStartupOperationState> completion =
                lifecycle.start().toCompletableFuture();

        scheduler.run(Duration.ofSeconds(1));
        scheduler.run(Duration.ofSeconds(2));
        scheduler.run(Duration.ofSeconds(4));

        assertEquals(
                BackendStartupOperationState.START_ACCEPTED,
                completion.join()
        );
        assertEquals(4, calls.get());
    }

    @Test
    void independentTimeoutTerminatesHungProviderAndIgnoresLateAcceptance() {
        OwnershipContext ownership = ownedContext();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        CompletableFuture<BackendStartResult> providerFuture =
                new CompletableFuture<>();
        BackendOrchestrationProvider provider = request -> providerFuture;

        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                provider,
                scheduler
        );
        CompletableFuture<BackendStartupOperationState> completion =
                lifecycle.start().toCompletableFuture();

        assertFalse(completion.isDone());
        scheduler.run(Duration.ofSeconds(30));

        assertEquals(
                BackendStartupOperationState.TIMED_OUT,
                completion.join()
        );
        verify(ownership.lifecycle()).stop();

        providerFuture.complete(BackendStartResult.accepted());

        assertEquals(
                BackendStartupOperationState.TIMED_OUT,
                lifecycle.state()
        );
        assertEquals(
                BackendStartupOperationState.TIMED_OUT,
                completion.join()
        );
    }

    @Test
    void ownershipFenceAbortsImmediatelyAndLateProviderCallbackCannotRevive() {
        OwnershipContext ownership = ownedContext();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        CompletableFuture<BackendStartResult> providerFuture =
                new CompletableFuture<>();
        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                request -> providerFuture,
                scheduler
        );
        CompletableFuture<BackendStartupOperationState> completion =
                lifecycle.start().toCompletableFuture();

        ownership.termination().complete(
                BackendBootstrapOwnershipState.FENCED
        );

        assertEquals(
                BackendStartupOperationState.FENCED,
                completion.join()
        );
        verify(ownership.lifecycle(), never()).stop();

        providerFuture.complete(BackendStartResult.accepted());

        assertEquals(
                BackendStartupOperationState.FENCED,
                lifecycle.state()
        );
    }

    @Test
    void cancelDuringRetryStopsOwnershipAndPreventsAnotherProviderCall() {
        OwnershipContext ownership = ownedContext();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        AtomicInteger calls = new AtomicInteger();
        BackendOrchestrationProvider provider = request -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    BackendStartResult.of(
                            BackendStartResult.Status.PROVIDER_UNAVAILABLE
                    )
            );
        };
        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                provider,
                scheduler
        );

        lifecycle.start();
        assertEquals(
                BackendStartupOperationState.RETRY_WAIT,
                lifecycle.state()
        );

        assertEquals(
                BackendStartupOperationState.CANCELLED,
                lifecycle.cancel().toCompletableFuture().join()
        );
        verify(ownership.lifecycle()).stop();

        scheduler.runIfPresent(Duration.ofSeconds(1));
        assertEquals(1, calls.get());
    }

    @Test
    void staleProviderAuthorityFencesAndReleasesLocalOwnership() {
        OwnershipContext ownership = ownedContext();
        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                request -> CompletableFuture.completedFuture(
                        BackendStartResult.of(
                                BackendStartResult.Status.STALE_AUTHORITY
                        )
                ),
                new ManualStartupScheduler()
        );

        assertEquals(
                BackendStartupOperationState.FENCED,
                lifecycle.start().toCompletableFuture().join()
        );
        verify(ownership.lifecycle()).stop();
    }

    @Test
    void terminalProviderRejectionFailsAndReleasesOwnership() {
        OwnershipContext ownership = ownedContext();
        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                request -> CompletableFuture.completedFuture(
                        BackendStartResult.of(
                                BackendStartResult.Status.CONFLICT
                        )
                ),
                new ManualStartupScheduler()
        );

        assertEquals(
                BackendStartupOperationState.FAILED,
                lifecycle.start().toCompletableFuture().join()
        );
        verify(ownership.lifecycle()).stop();
    }

    @Test
    void exceptionalProviderFailureFailsClosedAndStillStopsOwnership() {
        OwnershipContext ownership = ownedContext();
        IllegalStateException failure = new IllegalStateException("boom");
        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                request -> CompletableFuture.failedFuture(failure),
                new ManualStartupScheduler()
        );

        CompletionException observed = assertThrows(
                CompletionException.class,
                () -> lifecycle.start().toCompletableFuture().join()
        );

        assertEquals(failure, observed.getCause());
        assertEquals(
                BackendStartupOperationState.FAILED,
                lifecycle.state()
        );
        verify(ownership.lifecycle()).stop();
    }

    @Test
    void missingBootstrapAuthorityNeverCallsProvider() {
        OwnershipContext ownership = ownedContext();
        when(ownership.lifecycle().hasAuthority()).thenReturn(false);
        BackendOrchestrationProvider provider = mock(
                BackendOrchestrationProvider.class
        );
        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                provider,
                new ManualStartupScheduler()
        );

        assertEquals(
                BackendStartupOperationState.FENCED,
                lifecycle.start().toCompletableFuture().join()
        );
        verify(provider, never()).requestStart(any());
        verify(ownership.lifecycle(), never()).stop();
    }

    @Test
    void lifecycleIsSingleUse() {
        OwnershipContext ownership = ownedContext();
        BackendStartupOperationLifecycle lifecycle = lifecycle(
                ownership,
                request -> CompletableFuture.completedFuture(
                        BackendStartResult.accepted()
                ),
                new ManualStartupScheduler()
        );

        lifecycle.start().toCompletableFuture().join();

        assertThrows(IllegalStateException.class, lifecycle::start);
    }

    private static BackendStartupOperationLifecycle lifecycle(
            OwnershipContext ownership,
            BackendOrchestrationProvider provider,
            BackendStartupScheduler scheduler
    ) {
        return new BackendStartupOperationLifecycle(
                ownership.lifecycle(),
                provider,
                scheduler,
                CLOCK,
                POLICY
        );
    }

    private static OwnershipContext ownedContext() {
        BackendBootstrapLease lease = bootstrapLease();
        BackendBootstrapOwnershipLifecycle lifecycle = mock(
                BackendBootstrapOwnershipLifecycle.class
        );
        CompletableFuture<BackendBootstrapOwnershipState> termination =
                new CompletableFuture<>();

        when(lifecycle.termination()).thenReturn(termination);
        when(lifecycle.hasAuthority()).thenReturn(true);
        when(lifecycle.currentLease()).thenReturn(lease);
        when(lifecycle.stop()).thenReturn(
                CompletableFuture.completedFuture(Optional.empty())
        );

        return new OwnershipContext(lifecycle, lease, termination);
    }

    private static BackendBootstrapLease bootstrapLease() {
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
                41L
        );
    }

    private record OwnershipContext(
            BackendBootstrapOwnershipLifecycle lifecycle,
            BackendBootstrapLease lease,
            CompletableFuture<BackendBootstrapOwnershipState> termination
    ) {
    }

    private static final class ManualStartupScheduler
            implements BackendStartupScheduler {

        private final List<Scheduled> tasks = new ArrayList<>();

        @Override
        public Handle schedule(Runnable task, Duration delay) {
            Scheduled scheduled = new Scheduled(task, delay);
            tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        boolean hasActiveTask(Duration delay) {
            return tasks.stream().anyMatch(task ->
                    task.delay.equals(delay)
                            && !task.cancelled
                            && !task.executed
            );
        }

        int activeTaskCount() {
            return (int) tasks.stream().filter(task ->
                    !task.cancelled && !task.executed
            ).count();
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

        void runIfPresent(Duration delay) {
            tasks.stream()
                    .filter(task -> task.delay.equals(delay))
                    .filter(task -> !task.cancelled)
                    .filter(task -> !task.executed)
                    .findFirst()
                    .ifPresent(task -> {
                        task.executed = true;
                        task.task.run();
                    });
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

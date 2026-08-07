package com.theosfera.proxy.orchestration;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.coordination.BackendBootstrapAcquireResult;
import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipLifecycle;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipLifecycleFactory;
import com.theosfera.proxy.coordination.BackendBootstrapOwnershipState;
import com.theosfera.proxy.coordination.BackendBootstrapReleaseResult;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendColdStartCoordinatorTest {

    private static final UUID REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000002"
    );
    private static final UUID PLAYER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000003"
    );

    @Test
    void readyRequiresProviderAcceptanceReadinessAndExactRelease() {
        BackendBootstrapLease lease = lease();
        BackendBootstrapOwnershipLifecycle ownership = ownedLifecycle(lease);
        BackendBootstrapOwnershipLifecycleFactory factory = factory(
                ownership,
                BackendBootstrapAcquireResult.withLease(
                        BackendBootstrapAcquireResult.Status.ACQUIRED,
                        lease
                )
        );
        BackendOrchestrationProvider provider = request ->
                CompletableFuture.completedFuture(
                        BackendStartResult.accepted()
                );
        BackendReadinessProbe readinessProbe = mock(BackendReadinessProbe.class);
        when(readinessProbe.check("lobby-2")).thenReturn(
                BackendReadinessSnapshot.ready(
                        new BackendIdentity("lobby-2", BackendType.LOBBY)
                )
        );

        BackendColdStartResult result = coordinator(
                factory,
                provider,
                readinessProbe,
                new ManualStartupScheduler(),
                new ManualReadinessScheduler()
        ).start("lobby-2", REQUEST_ID, PLAYER_ID)
                .toCompletableFuture()
                .join();

        assertEquals(BackendColdStartResult.Status.READY, result.status());
        verify(ownership).stop();
    }

    @Test
    void targetBusyNeverCallsProvider() {
        BackendBootstrapOwnershipLifecycle ownership = mock(
                BackendBootstrapOwnershipLifecycle.class
        );
        BackendBootstrapOwnershipLifecycleFactory factory = factory(
                ownership,
                BackendBootstrapAcquireResult.withoutLease(
                        BackendBootstrapAcquireResult.Status.TARGET_BUSY
                )
        );
        BackendOrchestrationProvider provider = mock(
                BackendOrchestrationProvider.class
        );
        BackendReadinessProbe readinessProbe = mock(BackendReadinessProbe.class);

        BackendColdStartResult result = coordinator(
                factory,
                provider,
                readinessProbe,
                new ManualStartupScheduler(),
                new ManualReadinessScheduler()
        ).start("lobby-2", REQUEST_ID, PLAYER_ID)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendColdStartResult.Status.TARGET_BUSY,
                result.status()
        );
        verify(provider, never()).requestStart(any());
    }

    @Test
    void readinessTimeoutIsReportedAfterAcceptedStart() {
        BackendBootstrapLease lease = lease();
        BackendBootstrapOwnershipLifecycle ownership = ownedLifecycle(lease);
        BackendBootstrapOwnershipLifecycleFactory factory = factory(
                ownership,
                BackendBootstrapAcquireResult.withLease(
                        BackendBootstrapAcquireResult.Status.ACQUIRED,
                        lease
                )
        );
        BackendOrchestrationProvider provider = request ->
                CompletableFuture.completedFuture(
                        BackendStartResult.accepted()
                );
        BackendReadinessProbe readinessProbe = mock(BackendReadinessProbe.class);
        when(readinessProbe.check("lobby-2")).thenReturn(
                BackendReadinessSnapshot.of(
                        BackendReadinessStatus.CONTROL_NOT_AUTHENTICATED,
                        null,
                        null
                )
        );
        ManualReadinessScheduler readinessScheduler =
                new ManualReadinessScheduler();

        CompletableFuture<BackendColdStartResult> completion = coordinator(
                factory,
                provider,
                readinessProbe,
                new ManualStartupScheduler(),
                readinessScheduler
        ).start("lobby-2", REQUEST_ID, PLAYER_ID)
                .toCompletableFuture();

        readinessScheduler.run(Duration.ofSeconds(30));

        assertEquals(
                BackendColdStartResult.Status.READINESS_TIMED_OUT,
                completion.join().status()
        );
    }

    @Test
    void failedReadyReleaseDoesNotReportReady() {
        BackendBootstrapLease lease = lease();
        BackendBootstrapOwnershipLifecycle ownership = ownedLifecycle(lease);
        when(ownership.stop()).thenReturn(
                CompletableFuture.completedFuture(
                        Optional.of(new BackendBootstrapReleaseResult(
                                BackendBootstrapReleaseResult.Status
                                        .COORDINATION_UNAVAILABLE
                        ))
                )
        );
        BackendBootstrapOwnershipLifecycleFactory factory = factory(
                ownership,
                BackendBootstrapAcquireResult.withLease(
                        BackendBootstrapAcquireResult.Status.ACQUIRED,
                        lease
                )
        );
        BackendReadinessProbe readinessProbe = mock(BackendReadinessProbe.class);
        when(readinessProbe.check("lobby-2")).thenReturn(
                BackendReadinessSnapshot.ready(
                        new BackendIdentity("lobby-2", BackendType.LOBBY)
                )
        );

        BackendColdStartResult result = coordinator(
                factory,
                request -> CompletableFuture.completedFuture(
                        BackendStartResult.accepted()
                ),
                readinessProbe,
                new ManualStartupScheduler(),
                new ManualReadinessScheduler()
        ).start("lobby-2", REQUEST_ID, PLAYER_ID)
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendColdStartResult.Status.COORDINATION_UNAVAILABLE,
                result.status()
        );
    }

    private static BackendColdStartCoordinator coordinator(
            BackendBootstrapOwnershipLifecycleFactory factory,
            BackendOrchestrationProvider provider,
            BackendReadinessProbe readinessProbe,
            BackendStartupScheduler startupScheduler,
            BackendReadinessScheduler readinessScheduler
    ) {
        return new BackendColdStartCoordinator(
                factory,
                provider,
                startupScheduler,
                Clock.systemUTC(),
                new BackendStartupPolicy(
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(4),
                        2.0
                ),
                readinessProbe,
                readinessScheduler,
                new BackendReadinessPolicy(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1)
                )
        );
    }

    private static BackendBootstrapOwnershipLifecycleFactory factory(
            BackendBootstrapOwnershipLifecycle ownership,
            BackendBootstrapAcquireResult result
    ) {
        BackendBootstrapOwnershipLifecycleFactory factory = mock(
                BackendBootstrapOwnershipLifecycleFactory.class
        );
        when(factory.start("lobby-2", REQUEST_ID, PLAYER_ID))
                .thenReturn(
                        new BackendBootstrapOwnershipLifecycleFactory
                                .StartedOwnership(
                                ownership,
                                CompletableFuture.completedFuture(result)
                        )
                );
        return factory;
    }

    private static BackendBootstrapOwnershipLifecycle ownedLifecycle(
            BackendBootstrapLease lease
    ) {
        BackendBootstrapOwnershipLifecycle ownership = mock(
                BackendBootstrapOwnershipLifecycle.class
        );
        when(ownership.termination()).thenReturn(
                new CompletableFuture<BackendBootstrapOwnershipState>()
        );
        when(ownership.hasAuthority()).thenReturn(true);
        when(ownership.currentLease()).thenReturn(lease);
        when(ownership.stop()).thenReturn(
                CompletableFuture.completedFuture(
                        Optional.of(new BackendBootstrapReleaseResult(
                                BackendBootstrapReleaseResult.Status.RELEASED
                        ))
                )
        );
        return ownership;
    }

    private static BackendBootstrapLease lease() {
        return new BackendBootstrapLease(
                "lobby-2",
                REQUEST_ID,
                PLAYER_ID,
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

    private abstract static class AbstractManualScheduler {
        private final List<Scheduled> tasks = new ArrayList<>();

        protected Runnable register(Runnable task, Duration delay) {
            Scheduled scheduled = new Scheduled(task, delay);
            tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
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

    private static final class ManualStartupScheduler
            extends AbstractManualScheduler
            implements BackendStartupScheduler {
        @Override
        public Handle schedule(Runnable task, Duration delay) {
            Runnable cancel = register(task, delay);
            return cancel::run;
        }
    }

    private static final class ManualReadinessScheduler
            extends AbstractManualScheduler
            implements BackendReadinessScheduler {
        @Override
        public Handle schedule(Runnable task, Duration delay) {
            Runnable cancel = register(task, delay);
            return cancel::run;
        }
    }
}

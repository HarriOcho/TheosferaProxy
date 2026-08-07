package com.theosfera.proxy.coordination;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendBootstrapOwnershipLifecycleFactoryTest {

    private static final ProxyInstanceIdentity PROXY_IDENTITY =
            new ProxyInstanceIdentity(
                    "proxy-1",
                    UUID.fromString(
                            "11111111-2222-3333-4444-555555555555"
                    )
            );
    private static final ProxyMembershipLease MEMBERSHIP_LEASE =
            new ProxyMembershipLease(PROXY_IDENTITY, 41L);
    private static final UUID REQUEST_ID = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );
    private static final UUID PLAYER_ID = UUID.fromString(
            "12345678-1234-5678-9abc-def012345678"
    );

    @Test
    void startCapturesCurrentHealthyMembershipLease() {
        CoordinationStateRegistry states = new CoordinationStateRegistry();
        ProxyMembershipLifecycle membershipLifecycle =
                healthyMembershipLifecycle(states);
        FakeBackendBootstrapCoordinator coordinator =
                new FakeBackendBootstrapCoordinator();
        BackendBootstrapLease bootstrapLease = new BackendBootstrapLease(
                "lobby-2",
                REQUEST_ID,
                PLAYER_ID,
                MEMBERSHIP_LEASE,
                7L
        );
        coordinator.acquireResult = CompletableFuture.completedFuture(
                BackendBootstrapAcquireResult.withLease(
                        BackendBootstrapAcquireResult.Status.ACQUIRED,
                        bootstrapLease
                )
        );
        FakeBootstrapScheduler scheduler = new FakeBootstrapScheduler();
        BackendBootstrapLeasePolicy policy =
                BackendBootstrapLeasePolicy.productDefaults();
        BackendBootstrapOwnershipLifecycleFactory factory =
                new BackendBootstrapOwnershipLifecycleFactory(
                        coordinator,
                        membershipLifecycle,
                        scheduler,
                        Clock.systemUTC(),
                        policy
                );

        BackendBootstrapOwnershipLifecycleFactory.StartedOwnership started =
                factory.start("lobby-2", REQUEST_ID, PLAYER_ID);
        BackendBootstrapAcquireResult result = started.acquisition()
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapAcquireResult.Status.ACQUIRED,
                result.status()
        );
        assertNotNull(coordinator.acquireRequest);
        assertEquals(
                MEMBERSHIP_LEASE,
                coordinator.acquireRequest.membershipLease()
        );
        assertEquals(
                BackendBootstrapOwnershipState.OWNED,
                started.lifecycle().state()
        );
        assertEquals(
                bootstrapLease,
                started.lifecycle().currentLease()
        );
        assertEquals(policy.renewInterval(), scheduler.interval);
    }

    @Test
    void nonOwningAcquireRemainsNormalCoordinationResult() {
        CoordinationStateRegistry states = new CoordinationStateRegistry();
        ProxyMembershipLifecycle membershipLifecycle =
                healthyMembershipLifecycle(states);
        FakeBackendBootstrapCoordinator coordinator =
                new FakeBackendBootstrapCoordinator();
        coordinator.acquireResult = CompletableFuture.completedFuture(
                BackendBootstrapAcquireResult.withoutLease(
                        BackendBootstrapAcquireResult.Status.TARGET_BUSY
                )
        );
        BackendBootstrapOwnershipLifecycleFactory factory =
                new BackendBootstrapOwnershipLifecycleFactory(
                        coordinator,
                        membershipLifecycle,
                        new FakeBootstrapScheduler(),
                        Clock.systemUTC(),
                        BackendBootstrapLeasePolicy.productDefaults()
                );

        BackendBootstrapOwnershipLifecycleFactory.StartedOwnership started =
                factory.start("lobby-2", REQUEST_ID, PLAYER_ID);
        BackendBootstrapAcquireResult result = started.acquisition()
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendBootstrapAcquireResult.Status.TARGET_BUSY,
                result.status()
        );
        assertEquals(
                BackendBootstrapOwnershipState.STOPPED,
                started.lifecycle().state()
        );
    }

    @Test
    void degradedMembershipRejectsNewBootstrapLocally() {
        CoordinationStateRegistry states = new CoordinationStateRegistry();
        ProxyMembershipLifecycle membershipLifecycle =
                healthyMembershipLifecycle(states);
        states.set(CoordinationState.DEGRADED);
        FakeBackendBootstrapCoordinator coordinator =
                new FakeBackendBootstrapCoordinator();
        BackendBootstrapOwnershipLifecycleFactory factory =
                new BackendBootstrapOwnershipLifecycleFactory(
                        coordinator,
                        membershipLifecycle,
                        new FakeBootstrapScheduler(),
                        Clock.systemUTC(),
                        BackendBootstrapLeasePolicy.productDefaults()
                );

        assertThrows(
                IllegalStateException.class,
                () -> factory.start("lobby-2", REQUEST_ID, PLAYER_ID)
        );
        assertEquals(0, coordinator.acquireCalls);
    }

    @Test
    void healthyStateWithoutMembershipLeaseRejectsBootstrap() {
        CoordinationStateRegistry states = new CoordinationStateRegistry();
        ProxyMembershipLifecycle membershipLifecycle =
                new ProxyMembershipLifecycle(
                        new FakeMembershipCoordinator(),
                        new FakeMembershipScheduler(),
                        states,
                        Clock.systemUTC()
                );
        states.set(CoordinationState.HEALTHY);
        FakeBackendBootstrapCoordinator coordinator =
                new FakeBackendBootstrapCoordinator();
        BackendBootstrapOwnershipLifecycleFactory factory =
                new BackendBootstrapOwnershipLifecycleFactory(
                        coordinator,
                        membershipLifecycle,
                        new FakeBootstrapScheduler(),
                        Clock.systemUTC(),
                        BackendBootstrapLeasePolicy.productDefaults()
                );

        assertThrows(
                IllegalStateException.class,
                () -> factory.start("lobby-2", REQUEST_ID, PLAYER_ID)
        );
        assertEquals(0, coordinator.acquireCalls);
    }

    private static ProxyMembershipLifecycle healthyMembershipLifecycle(
            CoordinationStateRegistry states
    ) {
        FakeMembershipCoordinator coordinator =
                new FakeMembershipCoordinator();
        coordinator.acquireResult = CompletableFuture.completedFuture(
                ProxyMembershipAcquireResult.acquired(MEMBERSHIP_LEASE)
        );
        ProxyMembershipLifecycle lifecycle = new ProxyMembershipLifecycle(
                coordinator,
                new FakeMembershipScheduler(),
                states,
                Clock.systemUTC()
        );

        lifecycle.start(PROXY_IDENTITY).toCompletableFuture().join();
        assertEquals(CoordinationState.HEALTHY, lifecycle.state());
        return lifecycle;
    }

    private static final class FakeBackendBootstrapCoordinator
            implements BackendBootstrapCoordinator {

        private CompletionStage<BackendBootstrapAcquireResult> acquireResult =
                CompletableFuture.completedFuture(
                        BackendBootstrapAcquireResult.withoutLease(
                                BackendBootstrapAcquireResult.Status.TARGET_BUSY
                        )
                );
        private int acquireCalls;
        private BackendBootstrapAcquireRequest acquireRequest;

        @Override
        public CompletionStage<BackendBootstrapAcquireResult> acquire(
                BackendBootstrapAcquireRequest request
        ) {
            acquireCalls++;
            acquireRequest = request;
            return acquireResult;
        }

        @Override
        public CompletionStage<BackendBootstrapRenewResult> renew(
                BackendBootstrapLease expected
        ) {
            return CompletableFuture.completedFuture(
                    BackendBootstrapRenewResult.renewed(expected)
            );
        }

        @Override
        public CompletionStage<BackendBootstrapReleaseResult> releaseIfOwned(
                BackendBootstrapLease expected
        ) {
            return CompletableFuture.completedFuture(
                    new BackendBootstrapReleaseResult(
                            BackendBootstrapReleaseResult.Status.RELEASED
                    )
            );
        }
    }

    private static final class FakeBootstrapScheduler
            implements BackendBootstrapRenewalScheduler {

        private Duration interval;

        @Override
        public Handle schedule(Runnable task, Duration interval) {
            this.interval = interval;
            return () -> { };
        }
    }

    private static final class FakeMembershipCoordinator
            implements ProxyMembershipCoordinator {

        private CompletionStage<ProxyMembershipAcquireResult> acquireResult =
                CompletableFuture.completedFuture(
                        ProxyMembershipAcquireResult.acquired(MEMBERSHIP_LEASE)
                );

        @Override
        public CompletionStage<ProxyMembershipAcquireResult> acquire(
                ProxyInstanceIdentity identity
        ) {
            return acquireResult;
        }

        @Override
        public CompletionStage<ProxyMembershipRenewResult> renew(
                ProxyMembershipLease expected
        ) {
            return CompletableFuture.completedFuture(
                    ProxyMembershipRenewResult.renewed(expected)
            );
        }

        @Override
        public CompletionStage<Boolean> releaseIfOwned(
                ProxyMembershipLease expected
        ) {
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final class FakeMembershipScheduler
            implements ProxyMembershipRenewalScheduler {

        @Override
        public Handle schedule(Runnable task, Duration interval) {
            return () -> { };
        }
    }
}

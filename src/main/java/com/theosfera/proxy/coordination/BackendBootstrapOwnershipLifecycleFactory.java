package com.theosfera.proxy.coordination;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Creates and immediately starts one distributed backend-bootstrap ownership
 * lifecycle using the Proxy membership lease that is current at start time.
 *
 * <p>The local HEALTHY membership check is an admission guard only. Redis
 * remains authoritative and revalidates the exact membership owner and fencing
 * token atomically when the bootstrap lease is acquired.</p>
 */
public final class BackendBootstrapOwnershipLifecycleFactory {

    private final BackendBootstrapCoordinator coordinator;
    private final ProxyMembershipLifecycle membershipLifecycle;
    private final BackendBootstrapRenewalScheduler renewalScheduler;
    private final Clock clock;
    private final BackendBootstrapLeasePolicy leasePolicy;

    public BackendBootstrapOwnershipLifecycleFactory(
            BackendBootstrapCoordinator coordinator,
            ProxyMembershipLifecycle membershipLifecycle,
            BackendBootstrapRenewalScheduler renewalScheduler,
            Clock clock,
            BackendBootstrapLeasePolicy leasePolicy
    ) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator cannot be null"
        );
        this.membershipLifecycle = Objects.requireNonNull(
                membershipLifecycle,
                "membershipLifecycle cannot be null"
        );
        this.renewalScheduler = Objects.requireNonNull(
                renewalScheduler,
                "renewalScheduler cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.leasePolicy = Objects.requireNonNull(
                leasePolicy,
                "leasePolicy cannot be null"
        );
    }

    public StartedOwnership start(
            String targetBackendName,
            UUID requestId,
            UUID playerId
    ) {
        ProxyMembershipLease membershipLease =
                membershipLifecycle.currentLease();
        CoordinationState membershipState = membershipLifecycle.state();

        if (membershipState != CoordinationState.HEALTHY
                || membershipLease == null) {
            throw new IllegalStateException(
                    "backend bootstrap ownership requires current healthy Proxy membership"
            );
        }

        BackendBootstrapOwnershipLifecycle lifecycle =
                new BackendBootstrapOwnershipLifecycle(
                        coordinator,
                        renewalScheduler,
                        clock,
                        leasePolicy
                );
        BackendBootstrapAcquireRequest request =
                new BackendBootstrapAcquireRequest(
                        targetBackendName,
                        requestId,
                        playerId,
                        membershipLease
                );

        CompletionStage<BackendBootstrapAcquireResult> acquisition =
                lifecycle.start(request);
        return new StartedOwnership(lifecycle, acquisition);
    }

    public record StartedOwnership(
            BackendBootstrapOwnershipLifecycle lifecycle,
            CompletionStage<BackendBootstrapAcquireResult> acquisition
    ) {

        public StartedOwnership {
            Objects.requireNonNull(
                    lifecycle,
                    "lifecycle cannot be null"
            );
            Objects.requireNonNull(
                    acquisition,
                    "acquisition cannot be null"
            );
        }
    }
}

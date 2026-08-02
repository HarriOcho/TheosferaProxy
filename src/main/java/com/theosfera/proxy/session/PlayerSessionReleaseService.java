package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry.QuarantinedReleaseTimeout;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry.ReleaseTimeoutClaim;
import com.theosfera.proxy.session.PlayerSessionReleaseTimeoutScheduler.ReleaseTimeoutKey;
import com.theosfera.proxy.session.PlayerSessionReleaseTimeoutScheduler.ReleaseTimeoutPhase;
import com.theosfera.proxy.session.PlayerSessionReleaseTimeoutScheduler.ScheduledReleaseTimeout;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PlayerSessionReleaseService {

    private static final ScheduledReleaseTimeout NOOP_TIMEOUT =
            () -> {
            };

    private final PlayerSessionCoordinator sessionCoordinator;
    private final PlayerSessionLeaseBindingRegistry
            leaseBindingRegistry;
    private final PlayerSessionReleaseTimeoutScheduler
            releaseTimeoutScheduler;
    private final Logger logger;
    private final LifecycleProbe lifecycleProbe;
    private final ReentrantReadWriteLock lifecycleLock =
            new ReentrantReadWriteLock();
    private final Map<TimeoutIdentity, TimeoutRegistration>
            releaseTimeouts =
            new HashMap<>();

    private long lifecycleEpoch;

    public PlayerSessionReleaseService(
            PlayerSessionCoordinator sessionCoordinator,
            PlayerSessionLeaseBindingRegistry leaseBindingRegistry,
            PlayerSessionReleaseTimeoutScheduler
                    releaseTimeoutScheduler,
            Logger logger
    ) {
        this(
                sessionCoordinator,
                leaseBindingRegistry,
                releaseTimeoutScheduler,
                logger,
                LifecycleProbe.NOOP
        );
    }

    PlayerSessionReleaseService(
            PlayerSessionCoordinator sessionCoordinator,
            PlayerSessionLeaseBindingRegistry leaseBindingRegistry,
            PlayerSessionReleaseTimeoutScheduler
                    releaseTimeoutScheduler,
            Logger logger,
            LifecycleProbe lifecycleProbe
    ) {
        this.sessionCoordinator = Objects.requireNonNull(
                sessionCoordinator,
                "sessionCoordinator cannot be null"
        );

        this.leaseBindingRegistry =
                Objects.requireNonNull(
                        leaseBindingRegistry,
                        "leaseBindingRegistry cannot be null"
                );

        this.releaseTimeoutScheduler =
                Objects.requireNonNull(
                        releaseTimeoutScheduler,
                        "releaseTimeoutScheduler cannot be null"
                );

        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );

        this.lifecycleProbe = Objects.requireNonNull(
                lifecycleProbe,
                "lifecycleProbe cannot be null"
        );
    }

    public boolean releaseIfUnbound(
            PlayerSessionLease lease,
            ReleaseCallbacks callbacks
    ) {
        return releaseIfUnbound(
                lease,
                callbacks,
                false
        );
    }

    public boolean releaseRejectedAcquisitionIfUnbound(
            PlayerSessionLease lease,
            ReleaseCallbacks callbacks
    ) {
        return releaseIfUnbound(
                lease,
                callbacks,
                true
        );
    }

    private boolean releaseIfUnbound(
            PlayerSessionLease lease,
            ReleaseCallbacks callbacks,
            boolean allowClosedUnknownFloorCleanup
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        ReleaseCallbacks nonNullCallbacks =
                Objects.requireNonNull(
                        callbacks,
                        "callbacks cannot be null"
                );

        lifecycleLock.readLock().lock();
        try {
            long releaseEpoch = currentLifecycleEpoch();

            boolean releaseReserved =
                    allowClosedUnknownFloorCleanup
                            ? leaseBindingRegistry
                            .reserveRejectedAcquisitionReleaseIfUnbound(
                                    nonNullLease
                            )
                            : leaseBindingRegistry
                            .reserveReleaseIfUnbound(nonNullLease);

            if (!releaseReserved) {
                nonNullCallbacks.onNotReserved(nonNullLease);
                return false;
            }

            CompletionStage<Boolean> releaseStage;

            try {
                releaseStage = Objects.requireNonNull(
                        sessionCoordinator.releaseIfOwned(nonNullLease),
                        "sessionCoordinator.releaseIfOwned "
                                + "returned null"
                );
            } catch (RuntimeException exception) {
                leaseBindingRegistry.failReleaseBeforeExternalAttachment(
                        nonNullLease,
                        exception
                );
                nonNullCallbacks.onStartFailure(
                        nonNullLease,
                        exception
                );
                return true;
            }

            boolean releaseAttached =
                    leaseBindingRegistry.attachReleaseCompletion(
                            nonNullLease,
                            releaseStage
                    );

            if (!releaseAttached) {
                IllegalStateException exception =
                        new IllegalStateException(
                                "Release completion stage could not be "
                                        + "attached to the tracked lease"
                        );

                leaseBindingRegistry.failReleaseBeforeExternalAttachment(
                        nonNullLease,
                        exception
                );
                nonNullCallbacks.onStartFailure(
                        nonNullLease,
                        exception
                );
                return true;
            }

            TimeoutIdentity ownedTimeout =
                    scheduleReleaseTimeout(
                            nonNullLease,
                            releaseStage,
                            releaseEpoch
                    );

            releaseStage.whenComplete(
                    (released, failure) ->
                            handleReleaseCompletion(
                                    nonNullLease,
                                    releaseStage,
                                    releaseEpoch,
                                    ownedTimeout,
                                    released,
                                    failure,
                                    nonNullCallbacks
                            )
            );

            return true;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private void handleReleaseCompletion(
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage,
            long releaseEpoch,
            TimeoutIdentity ownedTimeout,
            Boolean released,
            Throwable failure,
            ReleaseCallbacks callbacks
    ) {
        if (ownedTimeout == null) {
            return;
        }

        CallbackAction callbackAction =
                withLifecyclePermit(
                        releaseEpoch,
                        () -> {
                            lifecycleProbe
                                    .afterExternalCallbackAccepted();
                            cancelTimeoutSafely(
                                    ownedTimeout
                            );

                            if (failure != null) {
                                cancelRetentionTimeoutSafely(
                                        lease,
                                        releaseStage,
                                        releaseEpoch
                                );
                                leaseBindingRegistry
                                        .failRelease(
                                                lease,
                                                releaseStage,
                                                failure
                                        );
                                return CallbackAction.failed();
                            }

                            boolean releaseSucceeded =
                                    Boolean.TRUE
                                            .equals(released);

                            cancelRetentionTimeoutSafely(
                                    lease,
                                    releaseStage,
                                    releaseEpoch
                            );
                            leaseBindingRegistry
                                    .completeRelease(
                                            lease,
                                            releaseStage,
                                            releaseSucceeded
                                    );
                            return CallbackAction.complete(
                                    releaseSucceeded
                            );
                        }
                );

        if (callbackAction == null) {
            return;
        }

        if (callbackAction.failure()) {
            callbacks.onFailure(
                    lease,
                    failure
            );
        } else {
            callbacks.onComplete(
                    lease,
                    callbackAction.released()
            );
        }
    }

    private TimeoutIdentity scheduleReleaseTimeout(
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage,
            long releaseEpoch
    ) {
        TimeoutIdentity identity =
                timeoutIdentity(
                        ReleaseTimeoutPhase
                                .OWNED_RELEASE_TIMEOUT,
                        lease,
                        releaseStage,
                        releaseEpoch
                );

        if (!reserveTimeout(identity)) {
            return null;
        }

        Runnable timeout =
                () -> handleReleaseTimeout(identity);

        try {
            ScheduledReleaseTimeout scheduledTimeout =
                    Objects.requireNonNull(
                            releaseTimeoutScheduler.schedule(
                                    identity.key(),
                                    timeout
                            ),
                            "releaseTimeoutScheduler.schedule returned null"
                    );

            if (!attachScheduledTimeout(
                    identity,
                    scheduledTimeout
            )) {
                cancelReleaseTimeoutSafely(scheduledTimeout);
            }

            return identity;
        } catch (RuntimeException exception) {
            UUID playerId = lease.session().playerId();

            logger.warn(
                    "No se pudo programar el timeout de liberacion "
                            + "de sesion para {} (token {}).",
                    playerId,
                    lease.fencingToken(),
                    exception
            );

            if (cancelTimeoutRegistration(identity)) {
                withLifecyclePermit(
                        releaseEpoch,
                        () -> {
                            failOwnedTimeoutScheduling(
                                    lease,
                                    releaseStage
                            );
                            return null;
                        }
                );
            }

            return null;
        }
    }

    private void cancelReleaseTimeoutSafely(
            ScheduledReleaseTimeout timeout
    ) {
        try {
            timeout.cancel();
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo cancelar el timeout de liberacion "
                            + "de sesion.",
                    exception
            );
        }
    }

    private void handleReleaseTimeout(
            TimeoutIdentity identity
    ) {
        Boolean handled =
                withLifecyclePermit(
                        identity.lifecycleEpoch(),
                        () -> {
                            if (!claimTimeout(identity)) {
                                return false;
                            }

                            lifecycleProbe
                                    .afterOwnedTimeoutClaimed();

                            ReleaseTimeoutClaim claim =
                                    leaseBindingRegistry
                                            .claimReleaseTimeoutWithEvictions(
                                                    identity.key()
                                                            .lease(),
                                                    identity.key()
                                                            .externalCompletion()
                                            );

                            for (QuarantinedReleaseTimeout evicted
                                    : claim.evictedQuarantines()) {
                                cancelRetentionTimeoutSafely(
                                        evicted.lease(),
                                        evicted.externalCompletion(),
                                        identity.lifecycleEpoch()
                                );
                            }

                            if (!claim.claimed()) {
                                return false;
                            }

                            return true;
                        }
                );

        if (!Boolean.TRUE.equals(handled)) {
            logger.debug(
                    "Timeout obsoleto de liberacion de sesion "
                            + "ignorado para {} (token {}).",
                    identity.key().playerId(),
                    identity.key().fencingToken()
            );
            return;
        }

        scheduleQuarantineRetentionTimeout(
                identity.key().lease(),
                identity.key().externalCompletion(),
                identity.lifecycleEpoch()
        );
    }

    private ScheduledReleaseTimeout scheduleQuarantineRetentionTimeout(
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage,
            long releaseEpoch
    ) {
        TimeoutIdentity identity =
                timeoutIdentity(
                        ReleaseTimeoutPhase
                                .QUARANTINE_RETENTION_TIMEOUT,
                        lease,
                        releaseStage,
                        releaseEpoch
                );

        if (!reserveTimeout(identity)) {
            return NOOP_TIMEOUT;
        }

        Runnable timeout =
                () -> handleQuarantineRetentionTimeout(identity);

        try {
            ScheduledReleaseTimeout scheduledTimeout =
                    Objects.requireNonNull(
                            releaseTimeoutScheduler.schedule(
                                    identity.key(),
                                    timeout
                            ),
                            "releaseTimeoutScheduler.schedule returned null"
                    );

            if (!attachScheduledTimeout(
                    identity,
                    scheduledTimeout
            )) {
                cancelReleaseTimeoutSafely(scheduledTimeout);
            }

            return scheduledTimeout;
        } catch (RuntimeException exception) {
            UUID playerId = lease.session().playerId();

            logger.warn(
                    "No se pudo programar la retencion de "
                            + "quarantine de sesion para {} "
                            + "(token {}).",
                    playerId,
                    lease.fencingToken(),
                    exception
            );

            if (cancelTimeoutRegistration(identity)) {
                withLifecyclePermit(
                        releaseEpoch,
                        () -> {
                            failRetentionTimeoutScheduling(
                                    lease,
                                    releaseStage
                            );
                            return null;
                        }
                );
            }

            return NOOP_TIMEOUT;
        }
    }

    private void handleQuarantineRetentionTimeout(
            TimeoutIdentity identity
    ) {
        Boolean claimed =
                withLifecyclePermit(
                        identity.lifecycleEpoch(),
                        () -> {
                            if (!claimTimeout(identity)) {
                                return false;
                            }

                            lifecycleProbe
                                    .afterRetentionTimeoutClaimed();

                            return leaseBindingRegistry
                                    .claimReleaseQuarantineRetentionTimeout(
                                            identity.key().lease(),
                                            identity.key()
                                                    .externalCompletion()
                                    );
                        }
                );

        if (!Boolean.TRUE.equals(claimed)) {
            logger.debug(
                    "Timeout obsoleto de retencion de quarantine "
                            + "ignorado para {} (token {}).",
                    identity.key().playerId(),
                    identity.key().fencingToken()
            );
        }
    }

    private void cancelRetentionTimeoutSafely(
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage,
            long releaseEpoch
    ) {
        cancelTimeoutSafely(
                timeoutIdentity(
                        ReleaseTimeoutPhase
                                .QUARANTINE_RETENTION_TIMEOUT,
                        lease,
                        releaseStage,
                        releaseEpoch
                )
        );
    }

    public void clear() {
        lifecycleLock.writeLock().lock();
        try {
            List<ScheduledReleaseTimeout> timeouts =
                    new ArrayList<>();

            synchronized (this) {
                lifecycleEpoch++;

                for (TimeoutRegistration registration
                        : releaseTimeouts.values()) {
                    if (registration.timeout() != null) {
                        timeouts.add(registration.timeout());
                    }
                }

                releaseTimeouts.clear();
            }

            for (ScheduledReleaseTimeout timeout : timeouts) {
                cancelReleaseTimeoutSafely(timeout);
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private long currentLifecycleEpoch() {
        synchronized (this) {
            return lifecycleEpoch;
        }
    }

    private boolean isCurrentLifecycle(long expectedEpoch) {
        synchronized (this) {
            return lifecycleEpoch == expectedEpoch;
        }
    }

    private <T> T withLifecyclePermit(
            long expectedEpoch,
            java.util.function.Supplier<T> action
    ) {
        lifecycleLock.readLock().lock();
        try {
            if (!isCurrentLifecycle(expectedEpoch)) {
                return null;
            }

            return action.get();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private TimeoutIdentity timeoutIdentity(
            ReleaseTimeoutPhase phase,
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage,
            long releaseEpoch
    ) {
        return new TimeoutIdentity(
                releaseEpoch,
                new ReleaseTimeoutKey(
                        phase,
                        lease.session().playerId(),
                        lease,
                        lease.fencingToken(),
                        releaseStage
                )
        );
    }

    private boolean reserveTimeout(TimeoutIdentity identity) {
        synchronized (this) {
            if (lifecycleEpoch != identity.lifecycleEpoch()
                    || releaseTimeouts.containsKey(identity)) {
                return false;
            }

            releaseTimeouts.put(
                    identity,
                    new TimeoutRegistration(
                            TimeoutState.SCHEDULING,
                            null
                    )
            );

            return true;
        }
    }

    private boolean attachScheduledTimeout(
            TimeoutIdentity identity,
            ScheduledReleaseTimeout timeout
    ) {
        synchronized (this) {
            TimeoutRegistration registration =
                    releaseTimeouts.get(identity);

            if (lifecycleEpoch != identity.lifecycleEpoch()
                    || registration == null
                    || registration.state()
                    != TimeoutState.SCHEDULING) {
                return false;
            }

            releaseTimeouts.put(
                    identity,
                    new TimeoutRegistration(
                            TimeoutState.SCHEDULED,
                            timeout
                    )
            );

            return true;
        }
    }

    private boolean claimTimeout(TimeoutIdentity identity) {
        synchronized (this) {
            TimeoutRegistration registration =
                    releaseTimeouts.get(identity);

            if (lifecycleEpoch != identity.lifecycleEpoch()
                    || registration == null
                    || registration.state()
                    == TimeoutState.CANCELLED
                    || registration.state()
                    == TimeoutState.CLAIMED) {
                return false;
            }

            releaseTimeouts.put(
                    identity,
                    new TimeoutRegistration(
                            TimeoutState.CLAIMED,
                            registration.timeout()
                    )
            );
            releaseTimeouts.remove(identity);
            return true;
        }
    }

    private boolean cancelTimeoutRegistration(
            TimeoutIdentity identity
    ) {
        synchronized (this) {
            TimeoutRegistration registration =
                    releaseTimeouts.get(identity);

            if (registration == null
                    || registration.state()
                    == TimeoutState.CANCELLED
                    || registration.state()
                    == TimeoutState.CLAIMED) {
                return false;
            }

            releaseTimeouts.remove(identity);
            return true;
        }
    }

    private void cancelTimeoutSafely(
            TimeoutIdentity identity
    ) {
        ScheduledReleaseTimeout timeout = null;

        synchronized (this) {
            TimeoutRegistration registration =
                    releaseTimeouts.get(identity);

            if (registration == null
                    || registration.state()
                    == TimeoutState.CANCELLED
                    || registration.state()
                    == TimeoutState.CLAIMED) {
                return;
            }

            releaseTimeouts.remove(identity);
            timeout = registration.timeout();
        }

        if (timeout != null) {
            cancelReleaseTimeoutSafely(timeout);
        }
    }

    private void failOwnedTimeoutScheduling(
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage
    ) {
        leaseBindingRegistry.failReleaseTimeoutScheduling(
                lease,
                releaseStage
        );
    }

    private void failRetentionTimeoutScheduling(
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage
    ) {
        leaseBindingRegistry
                .claimReleaseQuarantineRetentionTimeout(
                        lease,
                        releaseStage
                );
    }

    private enum TimeoutState {
        SCHEDULING,
        SCHEDULED,
        CLAIMED,
        CANCELLED
    }

    private record TimeoutIdentity(
            long lifecycleEpoch,
            ReleaseTimeoutKey key
    ) {

        private TimeoutIdentity {
            key = Objects.requireNonNull(
                    key,
                    "key cannot be null"
            );
        }
    }

    private record TimeoutRegistration(
            TimeoutState state,
            ScheduledReleaseTimeout timeout
    ) {

        private TimeoutRegistration {
            state = Objects.requireNonNull(
                    state,
                    "state cannot be null"
            );
        }
    }

    private record CallbackAction(
            boolean failure,
            boolean released
    ) {

        private static CallbackAction failed() {
            return new CallbackAction(
                    true,
                    false
            );
        }

        private static CallbackAction complete(boolean released) {
            return new CallbackAction(
                    false,
                    released
            );
        }
    }

    interface LifecycleProbe {

        LifecycleProbe NOOP =
                new LifecycleProbe() {
                };

        default void afterOwnedTimeoutClaimed() {
        }

        default void afterRetentionTimeoutClaimed() {
        }

        default void afterExternalCallbackAccepted() {
        }
    }

    public interface ReleaseCallbacks {

        default void onNotReserved(PlayerSessionLease lease) {
        }

        default void onStartFailure(
                PlayerSessionLease lease,
                RuntimeException failure
        ) {
        }

        default void onFailure(
                PlayerSessionLease lease,
                Throwable failure
        ) {
        }

        default void onComplete(
                PlayerSessionLease lease,
                boolean released
        ) {
        }
    }
}

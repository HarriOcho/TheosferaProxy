package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.session.PlayerSessionReleaseTimeoutScheduler.ReleaseTimeoutKey;
import com.theosfera.proxy.session.PlayerSessionReleaseTimeoutScheduler.ScheduledReleaseTimeout;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

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

    public PlayerSessionReleaseService(
            PlayerSessionCoordinator sessionCoordinator,
            PlayerSessionLeaseBindingRegistry leaseBindingRegistry,
            PlayerSessionReleaseTimeoutScheduler
                    releaseTimeoutScheduler,
            Logger logger
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
    }

    public boolean releaseIfUnbound(
            PlayerSessionLease lease,
            ReleaseCallbacks callbacks
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

        boolean releaseReserved =
                leaseBindingRegistry
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

        ScheduledReleaseTimeout timeout =
                scheduleReleaseTimeout(
                        nonNullLease,
                        releaseStage
                );

        releaseStage.whenComplete(
                (released, failure) -> {
                    cancelReleaseTimeoutSafely(timeout);

                    if (failure != null) {
                        leaseBindingRegistry.failRelease(
                                nonNullLease,
                                releaseStage,
                                failure
                        );
                        nonNullCallbacks.onFailure(
                                nonNullLease,
                                failure
                        );
                        return;
                    }

                    boolean releaseSucceeded =
                            Boolean.TRUE.equals(released);

                    leaseBindingRegistry.completeRelease(
                            nonNullLease,
                            releaseStage,
                            releaseSucceeded
                    );
                    nonNullCallbacks.onComplete(
                            nonNullLease,
                            releaseSucceeded
                    );
                }
        );

        return true;
    }

    private ScheduledReleaseTimeout scheduleReleaseTimeout(
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage
    ) {
        Runnable timeout =
                () -> handleReleaseTimeout(
                        lease,
                        releaseStage
                );

        try {
            return Objects.requireNonNull(
                    releaseTimeoutScheduler.schedule(
                            new ReleaseTimeoutKey(
                                    lease.session().playerId(),
                                    lease,
                                    lease.fencingToken(),
                                    releaseStage
                            ),
                            timeout
                    ),
                    "releaseTimeoutScheduler.schedule returned null"
            );
        } catch (RuntimeException exception) {
            UUID playerId = lease.session().playerId();

            logger.warn(
                    "No se pudo programar el timeout de liberación "
                            + "de sesión para {} (token {}).",
                    playerId,
                    lease.fencingToken(),
                    exception
            );

            timeout.run();
            return NOOP_TIMEOUT;
        }
    }

    private void cancelReleaseTimeoutSafely(
            ScheduledReleaseTimeout timeout
    ) {
        try {
            timeout.cancel();
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo cancelar el timeout de liberación "
                            + "de sesión.",
                    exception
            );
        }
    }

    private void handleReleaseTimeout(
            PlayerSessionLease lease,
            CompletionStage<Boolean> releaseStage
    ) {
        boolean claimed =
                leaseBindingRegistry.claimReleaseTimeout(
                        lease,
                        releaseStage
                );

        if (!claimed) {
            logger.debug(
                    "Timeout obsoleto de liberación de sesión "
                            + "ignorado para {} (token {}).",
                    lease.session().playerId(),
                    lease.fencingToken()
            );
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

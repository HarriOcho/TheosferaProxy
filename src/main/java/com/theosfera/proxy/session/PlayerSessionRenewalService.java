package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionRenewResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class PlayerSessionRenewalService {

    private static final Component AUTHORITY_LOST_MESSAGE =
            Component.text("Player session authority was lost. Please reconnect.");

    private final ProxyServer proxyServer;
    private final PlayerSessionCoordinator coordinator;
    private final PlayerSessionLeaseBindingRegistry bindingRegistry;
    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final PlayerSessionRenewalScheduler scheduler;
    private final Clock clock;
    private final Duration sessionTtl;
    private final Duration renewInterval;
    private final Logger logger;
    private final Object lock = new Object();
    private final Map<UUID, TrackedLease> tracked = new HashMap<>();

    private PlayerSessionRenewalScheduler.Handle scheduledHandle;
    private long lifecycleEpoch;

    public PlayerSessionRenewalService(
            ProxyServer proxyServer,
            PlayerSessionCoordinator coordinator,
            PlayerSessionLeaseBindingRegistry bindingRegistry,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerSessionRenewalScheduler scheduler,
            Clock clock,
            Duration sessionTtl,
            Duration renewInterval,
            Logger logger
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator cannot be null"
        );
        this.bindingRegistry = Objects.requireNonNull(
                bindingRegistry,
                "bindingRegistry cannot be null"
        );
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
        this.scheduler = Objects.requireNonNull(
                scheduler,
                "scheduler cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.sessionTtl = requirePositive(sessionTtl, "sessionTtl");
        this.renewInterval = requirePositive(
                renewInterval,
                "renewInterval"
        );
        if (this.renewInterval.compareTo(this.sessionTtl) >= 0) {
            throw new IllegalArgumentException(
                    "renewInterval must be shorter than sessionTtl"
            );
        }
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    public void start() {
        synchronized (lock) {
            if (scheduledHandle != null) {
                throw new IllegalStateException(
                        "player session renewal service is already started"
                );
            }
            long epoch = ++lifecycleEpoch;
            scheduledHandle = Objects.requireNonNull(
                    scheduler.schedule(() -> tick(epoch), renewInterval),
                    "scheduler.schedule returned null"
            );
        }
    }

    public void stop() {
        PlayerSessionRenewalScheduler.Handle handle;
        synchronized (lock) {
            ++lifecycleEpoch;
            handle = scheduledHandle;
            scheduledHandle = null;
            tracked.clear();
        }
        if (handle != null) {
            handle.cancel();
        }
    }

    void tick(long epoch) {
        Map<UUID, AuthenticatedPlayerSession> sessions =
                sessionRegistry.snapshot();

        synchronized (lock) {
            if (epoch != lifecycleEpoch || scheduledHandle == null) {
                return;
            }

            tracked.keySet().removeIf(playerId ->
                    !sessions.containsKey(playerId)
                            || currentBoundLease(playerId) == null
            );

            for (UUID playerId : sessions.keySet()) {
                Player player = proxyServer.getPlayer(playerId).orElse(null);
                if (player == null) {
                    tracked.remove(playerId);
                    continue;
                }

                PlayerSessionLease lease =
                        bindingRegistry.find(player).orElse(null);
                if (lease == null) {
                    tracked.remove(playerId);
                    continue;
                }

                TrackedLease state = tracked.get(playerId);
                if (state == null || !state.lease().equals(lease)) {
                    state = new TrackedLease(lease, 0L, false);
                    tracked.put(playerId, state);
                }

                if (state.ownershipDeadlineMillis() > 0L
                        && clock.millis() >= state.ownershipDeadlineMillis()) {
                    tracked.remove(playerId);
                    revoke(player, lease, "session lease deadline expired");
                    continue;
                }

                if (state.renewInFlight()) {
                    continue;
                }

                tracked.put(
                        playerId,
                        new TrackedLease(
                                lease,
                                state.ownershipDeadlineMillis(),
                                true
                        )
                );
                startRenew(epoch, player, lease);
            }
        }
    }

    private PlayerSessionLease currentBoundLease(UUID playerId) {
        Player player = proxyServer.getPlayer(playerId).orElse(null);
        if (player == null) {
            return null;
        }
        return bindingRegistry.find(player).orElse(null);
    }

    private void startRenew(
            long epoch,
            Player player,
            PlayerSessionLease lease
    ) {
        CompletionStage<PlayerSessionRenewResult> stage;
        try {
            stage = Objects.requireNonNull(
                    coordinator.renew(lease),
                    "coordinator.renew returned null"
            );
        } catch (RuntimeException exception) {
            handleRenewCompletion(epoch, player, lease, null, exception);
            return;
        }

        stage.whenComplete((result, failure) ->
                handleRenewCompletion(
                        epoch,
                        player,
                        lease,
                        result,
                        failure
                )
        );
    }

    private void handleRenewCompletion(
            long epoch,
            Player player,
            PlayerSessionLease lease,
            PlayerSessionRenewResult result,
            Throwable failure
    ) {
        boolean revoke = false;
        String revokeReason = null;

        synchronized (lock) {
            if (epoch != lifecycleEpoch || scheduledHandle == null) {
                return;
            }

            UUID playerId = lease.session().playerId();
            TrackedLease state = tracked.get(playerId);
            if (state == null || !state.lease().equals(lease)) {
                return;
            }

            if (!bindingRegistry.find(player).filter(lease::equals).isPresent()) {
                tracked.remove(playerId);
                return;
            }

            long now = clock.millis();

            if (failure != null || result == null) {
                if (state.ownershipDeadlineMillis() == 0L
                        || now >= state.ownershipDeadlineMillis()) {
                    tracked.remove(playerId);
                    revoke = true;
                    revokeReason = "session renewal failed without authority";
                } else {
                    tracked.put(
                            playerId,
                            new TrackedLease(
                                    lease,
                                    state.ownershipDeadlineMillis(),
                                    false
                            )
                    );
                }
            } else {
                switch (result.status()) {
                    case RENEWED -> {
                        PlayerSessionLease renewed =
                                result.lease().orElseThrow();
                        if (!renewed.equals(lease)) {
                            tracked.remove(playerId);
                            revoke = true;
                            revokeReason = "session renewal returned mismatched lease";
                        } else {
                            tracked.put(
                                    playerId,
                                    new TrackedLease(
                                            lease,
                                            Math.addExact(
                                                    now,
                                                    sessionTtl.toMillis()
                                            ),
                                            false
                                    )
                            );
                        }
                    }
                    case COORDINATION_UNAVAILABLE -> {
                        if (state.ownershipDeadlineMillis() == 0L
                                || now >= state.ownershipDeadlineMillis()) {
                            tracked.remove(playerId);
                            revoke = true;
                            revokeReason = "session coordination unavailable";
                        } else {
                            tracked.put(
                                    playerId,
                                    new TrackedLease(
                                            lease,
                                            state.ownershipDeadlineMillis(),
                                            false
                                    )
                            );
                        }
                    }
                    case NOT_FOUND, NOT_OWNER, CONFLICT -> {
                        tracked.remove(playerId);
                        revoke = true;
                        revokeReason = "session authority rejected by coordinator";
                    }
                }
            }
        }

        if (revoke) {
            revoke(player, lease, revokeReason);
        }
    }

    private void revoke(
            Player player,
            PlayerSessionLease lease,
            String reason
    ) {
        if (bindingRegistry.removeIfMatches(player, lease).isEmpty()) {
            return;
        }

        sessionRegistry.removeIfMatches(lease.session());
        logger.warn(
                "Sesion distribuida revocada para {} (token {}): {}.",
                lease.session().playerId(),
                lease.fencingToken(),
                reason
        );
        player.disconnect(AUTHORITY_LOST_MESSAGE);
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration nonNull = Objects.requireNonNull(
                value,
                name + " cannot be null"
        );
        if (nonNull.isZero()
                || nonNull.isNegative()
                || nonNull.toMillis() <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return nonNull;
    }

    private record TrackedLease(
            PlayerSessionLease lease,
            long ownershipDeadlineMillis,
            boolean renewInFlight
    ) {
    }
}

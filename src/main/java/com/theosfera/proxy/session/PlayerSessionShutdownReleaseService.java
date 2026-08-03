package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PlayerSessionShutdownReleaseService {

    private final ProxyServer proxyServer;
    private final PlayerSessionCoordinator coordinator;
    private final PlayerSessionLeaseBindingRegistry bindingRegistry;
    private final PlayerServerPresenceRegistry presenceRegistry;
    private final PlayerPresenceRuntimeService presenceRuntimeService;
    private final Logger logger;

    public PlayerSessionShutdownReleaseService(
            ProxyServer proxyServer,
            PlayerSessionCoordinator coordinator,
            PlayerSessionLeaseBindingRegistry bindingRegistry,
            Logger logger
    ) {
        this(
                proxyServer,
                coordinator,
                bindingRegistry,
                null,
                null,
                logger
        );
    }

    public PlayerSessionShutdownReleaseService(
            ProxyServer proxyServer,
            PlayerSessionCoordinator coordinator,
            PlayerSessionLeaseBindingRegistry bindingRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PlayerPresenceRuntimeService presenceRuntimeService,
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
        this.presenceRegistry = presenceRegistry;
        this.presenceRuntimeService = presenceRuntimeService;
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public CompletionStage<ReleaseSummary> releaseBoundSessions() {
        List<OwnedSessionState> states = snapshotAndFenceBindings();
        List<CompletableFuture<Boolean>> releases = new ArrayList<>();

        for (OwnedSessionState state : states) {
            releases.add(startRelease(state));
        }

        if (releases.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new ReleaseSummary(0, 0)
            );
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(
                releases.toArray(CompletableFuture[]::new)
        );

        return all.thenApply(ignored -> {
            int released = 0;
            for (CompletableFuture<Boolean> release : releases) {
                if (Boolean.TRUE.equals(release.getNow(false))) {
                    released++;
                }
            }
            return new ReleaseSummary(releases.size(), released);
        });
    }

    private List<OwnedSessionState> snapshotAndFenceBindings() {
        synchronized (bindingRegistry) {
            List<OwnedSessionState> states = new ArrayList<>();

            for (Player player : proxyServer.getAllPlayers()) {
                bindingRegistry.find(player).ifPresent(lease ->
                        states.add(
                                new OwnedSessionState(
                                        lease,
                                        presenceRegistry == null
                                                ? Optional.empty()
                                                : presenceRegistry.find(
                                                        lease.session().playerId()
                                                )
                                )
                        )
                );
            }

            bindingRegistry.clear();
            return List.copyOf(states);
        }
    }

    private CompletableFuture<Boolean> startRelease(
            OwnedSessionState state
    ) {
        if (presenceRuntimeService == null || state.presence().isEmpty()) {
            return startSessionRelease(state.lease());
        }

        final CompletionStage<?> presenceStage;
        try {
            presenceStage = Objects.requireNonNull(
                    presenceRuntimeService.removeIfOwned(
                            state.lease(),
                            state.presence().orElseThrow()
                    ),
                    "presenceRuntimeService.removeIfOwned returned null"
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo iniciar la retirada de presencia durante shutdown para {}.",
                    state.lease().session().playerId(),
                    exception
            );
            return startSessionRelease(state.lease());
        }

        return presenceStage.handle((result, failure) -> {
            if (failure != null) {
                logger.warn(
                        "Fallo al retirar la presencia durante shutdown para {}.",
                        state.lease().session().playerId(),
                        failure
                );
            }
            return null;
        }).thenCompose(ignored -> startSessionRelease(state.lease()))
                .toCompletableFuture();
    }

    private CompletableFuture<Boolean> startSessionRelease(
            PlayerSessionLease lease
    ) {
        final CompletionStage<Boolean> stage;
        try {
            stage = Objects.requireNonNull(
                    coordinator.releaseIfOwned(lease),
                    "coordinator.releaseIfOwned returned null"
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo iniciar la liberacion de sesion durante shutdown para {} (token {}).",
                    lease.session().playerId(),
                    lease.fencingToken(),
                    exception
            );
            return CompletableFuture.completedFuture(false);
        }

        return stage.handle((released, failure) -> {
            if (failure != null) {
                logger.warn(
                        "Fallo al liberar la sesion durante shutdown para {} (token {}).",
                        lease.session().playerId(),
                        lease.fencingToken(),
                        failure
                );
                return false;
            }
            return Boolean.TRUE.equals(released);
        }).toCompletableFuture();
    }

    private record OwnedSessionState(
            PlayerSessionLease lease,
            Optional<PlayerServerPresence> presence
    ) {
        private OwnedSessionState {
            Objects.requireNonNull(lease, "lease cannot be null");
            Objects.requireNonNull(presence, "presence cannot be null");
        }
    }

    public record ReleaseSummary(int attempted, int released) {
        public ReleaseSummary {
            if (attempted < 0 || released < 0 || released > attempted) {
                throw new IllegalArgumentException(
                        "invalid shutdown release summary"
                );
            }
        }

        public boolean complete() {
            return attempted == released;
        }
    }
}

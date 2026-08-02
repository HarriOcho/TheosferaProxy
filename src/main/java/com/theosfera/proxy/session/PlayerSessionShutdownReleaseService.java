package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PlayerSessionShutdownReleaseService {

    private final ProxyServer proxyServer;
    private final PlayerSessionCoordinator coordinator;
    private final PlayerSessionLeaseBindingRegistry bindingRegistry;
    private final Logger logger;

    public PlayerSessionShutdownReleaseService(
            ProxyServer proxyServer,
            PlayerSessionCoordinator coordinator,
            PlayerSessionLeaseBindingRegistry bindingRegistry,
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
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public CompletionStage<ReleaseSummary> releaseBoundSessions() {
        List<CompletableFuture<Boolean>> releases = new ArrayList<>();

        for (Player player : proxyServer.getAllPlayers()) {
            PlayerSessionLease lease = bindingRegistry
                    .find(player)
                    .orElse(null);

            if (lease == null) {
                continue;
            }

            releases.add(startRelease(lease));
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

    private CompletableFuture<Boolean> startRelease(
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

package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityCoordinator;
import com.theosfera.proxy.coordination.BackendCapacityHandoffLifecycle;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class BackendCapacityHandoffService
        implements BackendCapacityHandoffLifecycle {

    private final BackendCapacityCoordinator capacityCoordinator;
    private final BackendCapacityHandoffRegistry registry;
    private final Logger logger;

    public BackendCapacityHandoffService(
            BackendCapacityCoordinator capacityCoordinator,
            BackendCapacityHandoffRegistry registry,
            Logger logger
    ) {
        this.capacityCoordinator = Objects.requireNonNull(
                capacityCoordinator,
                "capacityCoordinator cannot be null"
        );
        this.registry = Objects.requireNonNull(
                registry,
                "registry cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public BackendCapacityHandoffRegistrationResult registerAfterConnectionSuccess(
            BackendCapacityReserveRequest request
    ) {
        return registry.register(
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                )
        );
    }

    @Override
    public void onPresenceConfirmed(
            PlayerSessionLease sessionLease,
            String backendName
    ) {
        PlayerSessionLease nonNullLease = Objects.requireNonNull(
                sessionLease,
                "sessionLease cannot be null"
        );
        String nonNullBackendName = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        );

        if (nonNullBackendName.isBlank()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
        }

        Optional<BackendCapacityReserveRequest> matching = registry
                .findByPlayer(nonNullLease.session().playerId())
                .filter(request ->
                        request.sessionLease().equals(nonNullLease)
                                && request.reservation()
                                .backendName()
                                .equals(nonNullBackendName)
                );

        if (matching.isEmpty()) {
            return;
        }

        BackendCapacityReserveRequest request = matching.orElseThrow();
        if (registry.removeIfMatches(request).isEmpty()) {
            return;
        }

        releaseAfterConfirmedPresence(request);
    }

    @Override
    public CompletionStage<Boolean> releaseForDisconnect(
            PlayerSessionLease sessionLease
    ) {
        PlayerSessionLease nonNullLease = Objects.requireNonNull(
                sessionLease,
                "sessionLease cannot be null"
        );

        Optional<BackendCapacityReserveRequest> removed =
                registry.removeForSessionLease(nonNullLease);

        if (removed.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        BackendCapacityReserveRequest request = removed.orElseThrow();
        final CompletionStage<Boolean> stage;

        try {
            stage = capacityCoordinator.releaseIfOwned(request);
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo iniciar la liberacion de capacidad distribuida para {} durante disconnect; TTL actuara como fallback.",
                    nonNullLease.session().playerId(),
                    exception
            );
            return CompletableFuture.completedFuture(false);
        }

        if (stage == null) {
            logger.warn(
                    "La liberacion de capacidad distribuida para {} devolvio un stage nulo durante disconnect; TTL actuara como fallback.",
                    nonNullLease.session().playerId()
            );
            return CompletableFuture.completedFuture(false);
        }

        return stage.handle((released, failure) -> {
            if (failure != null) {
                logger.warn(
                        "Fallo al liberar capacidad distribuida para {} durante disconnect; TTL actuara como fallback.",
                        nonNullLease.session().playerId(),
                        failure
                );
                return false;
            }

            if (!Boolean.TRUE.equals(released)) {
                logger.debug(
                        "No se confirmo la liberacion exacta de capacidad distribuida para {} durante disconnect; TTL cubrira cualquier reserva remanente.",
                        nonNullLease.session().playerId()
                );
                return false;
            }

            return true;
        });
    }

    public int pendingHandoffs() {
        return registry.size();
    }

    public void clear() {
        registry.clear();
    }

    private void releaseAfterConfirmedPresence(
            BackendCapacityReserveRequest request
    ) {
        final CompletionStage<Boolean> stage;

        try {
            stage = capacityCoordinator.releaseIfOwned(request);
        } catch (RuntimeException exception) {
            logger.warn(
                    "Presencia Redis confirmada para {} en {}, pero no se pudo iniciar el release exacto de capacidad; TTL actuara como fallback.",
                    request.reservation().playerId(),
                    request.reservation().backendName(),
                    exception
            );
            return;
        }

        if (stage == null) {
            logger.warn(
                    "Presencia Redis confirmada para {} en {}, pero el release exacto de capacidad devolvio un stage nulo; TTL actuara como fallback.",
                    request.reservation().playerId(),
                    request.reservation().backendName()
            );
            return;
        }

        stage.whenComplete((released, failure) -> {
            if (failure != null) {
                logger.warn(
                        "Presencia Redis confirmada para {} en {}, pero fallo el release exacto de capacidad; TTL actuara como fallback.",
                        request.reservation().playerId(),
                        request.reservation().backendName(),
                        failure
                );
                return;
            }

            if (Boolean.TRUE.equals(released)) {
                logger.debug(
                        "Handoff de capacidad distribuida completado para {} en {}.",
                        request.reservation().playerId(),
                        request.reservation().backendName()
                );
            } else {
                logger.debug(
                        "La presencia Redis de {} en {} ya es autoritativa, pero no se confirmo el release exacto de su reserva; TTL cubrira cualquier remanente.",
                        request.reservation().playerId(),
                        request.reservation().backendName()
                );
            }
        });
    }
}

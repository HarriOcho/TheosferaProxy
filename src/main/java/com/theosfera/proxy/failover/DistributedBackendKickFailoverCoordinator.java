package com.theosfera.proxy.failover;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.transfer.BackendCapacityHandoffRegistrationResult;
import com.theosfera.proxy.transfer.BackendCapacityHandoffService;
import com.theosfera.proxy.transfer.DistributedBackendCapacityReleaseService;
import com.theosfera.proxy.transfer.DistributedResolvedTargetAllocation;
import com.theosfera.proxy.transfer.DistributedResolvedTargetAllocationService;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Coordinates kick failover against distributed backend capacity.
 *
 * <p>This coordinator deliberately consumes only the resolved/active-only
 * allocation primitive. It has no bootstrap dependency and cannot request a
 * cold backend. Redis/session authority failures are terminal and fail closed;
 * only ordinary target absence or {@code NO_CAPACITY} may fall back from the
 * source backend type to Lobby.</p>
 */
public final class DistributedBackendKickFailoverCoordinator {

    private final DistributedResolvedTargetAllocationService allocationService;
    private final PendingPlayerFailoverRegistry failoverRegistry;
    private final DistributedBackendCapacityReleaseService releaseService;
    private final BackendCapacityHandoffService handoffService;
    private final Supplier<UUID> requestIdGenerator;
    private final Logger logger;

    public DistributedBackendKickFailoverCoordinator(
            DistributedResolvedTargetAllocationService allocationService,
            PendingPlayerFailoverRegistry failoverRegistry,
            DistributedBackendCapacityReleaseService releaseService,
            BackendCapacityHandoffService handoffService,
            Logger logger
    ) {
        this(
                allocationService,
                failoverRegistry,
                releaseService,
                handoffService,
                UUID::randomUUID,
                logger
        );
    }

    DistributedBackendKickFailoverCoordinator(
            DistributedResolvedTargetAllocationService allocationService,
            PendingPlayerFailoverRegistry failoverRegistry,
            DistributedBackendCapacityReleaseService releaseService,
            BackendCapacityHandoffService handoffService,
            Supplier<UUID> requestIdGenerator,
            Logger logger
    ) {
        this.allocationService = Objects.requireNonNull(
                allocationService,
                "allocationService cannot be null"
        );
        this.failoverRegistry = Objects.requireNonNull(
                failoverRegistry,
                "failoverRegistry cannot be null"
        );
        this.releaseService = Objects.requireNonNull(
                releaseService,
                "releaseService cannot be null"
        );
        this.handoffService = Objects.requireNonNull(
                handoffService,
                "handoffService cannot be null"
        );
        this.requestIdGenerator = Objects.requireNonNull(
                requestIdGenerator,
                "requestIdGenerator cannot be null"
        );
        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    public CompletionStage<BackendKickFailoverResolution> resolve(
            Player player,
            BackendType sourceType,
            Set<String> excludedServerNames,
            Component disconnectReason
    ) {
        Player nonNullPlayer = Objects.requireNonNull(
                player,
                "player cannot be null"
        );
        BackendType nonNullSourceType = Objects.requireNonNull(
                sourceType,
                "sourceType cannot be null"
        );
        Set<String> nonNullExclusions = Set.copyOf(
                Objects.requireNonNull(
                        excludedServerNames,
                        "excludedServerNames cannot be null"
                )
        );
        Component nonNullReason = Objects.requireNonNull(
                disconnectReason,
                "disconnectReason cannot be null"
        );

        if (nonNullSourceType == BackendType.AUTH) {
            return completed(
                    BackendKickFailoverResolution.disconnect(nonNullReason)
            );
        }

        UUID playerId = nonNullPlayer.getUniqueId();
        if (!failoverRegistry.reserve(playerId)) {
            return completed(BackendKickFailoverResolution.ignored());
        }

        CompletionStage<BackendKickFailoverResolution> resolutionStage =
                resolveForType(
                        nonNullPlayer,
                        nonNullSourceType,
                        nonNullExclusions,
                        nonNullReason,
                        nonNullSourceType != BackendType.LOBBY
                );

        if (resolutionStage == null) {
            failoverRegistry.clear(playerId);
            return completed(
                    BackendKickFailoverResolution.disconnect(nonNullReason)
            );
        }

        return resolutionStage.handle((resolution, failure) -> {
            if (failure == null && resolution != null) {
                return resolution;
            }

            failoverRegistry.clear(playerId);
            logger.warn(
                    "Kick failover distribuido fallo para {}; se aplicara fail-closed.",
                    playerId,
                    failure
            );
            return BackendKickFailoverResolution.disconnect(nonNullReason);
        });
    }

    public void completeSuccessfulConnection(
            UUID playerId,
            String connectedBackendName
    ) {
        UUID nonNullPlayerId = Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );
        String nonNullBackendName = Objects.requireNonNull(
                connectedBackendName,
                "connectedBackendName cannot be null"
        );
        if (nonNullBackendName.isBlank()) {
            throw new IllegalArgumentException(
                    "connectedBackendName cannot be blank"
            );
        }

        Optional<BackendCapacityReserveRequest> pending =
                failoverRegistry.clearDistributedForCompletion(nonNullPlayerId);
        if (pending.isEmpty()) {
            return;
        }

        BackendCapacityReserveRequest request = pending.orElseThrow();
        if (!request.reservation().backendName().equals(nonNullBackendName)) {
            logger.warn(
                    "Kick failover de {} conecto a {} pero reservaba {}; se intentara release exacto sin handoff.",
                    nonNullPlayerId,
                    nonNullBackendName,
                    request.reservation().backendName()
            );
            releaseBestEffort(request);
            return;
        }

        final BackendCapacityHandoffRegistrationResult result;
        try {
            result = handoffService.registerAfterConnectionSuccess(request);
        } catch (RuntimeException exception) {
            logger.warn(
                    "Conexion de kick failover confirmada para {} en {}, pero no se pudo registrar el handoff; TTL actuara como fallback.",
                    nonNullPlayerId,
                    nonNullBackendName,
                    exception
            );
            return;
        }

        if (result == BackendCapacityHandoffRegistrationResult.PLAYER_BUSY
                || result == BackendCapacityHandoffRegistrationResult.REQUEST_ID_CONFLICT) {
            logger.warn(
                    "Conexion de kick failover confirmada para {} en {}, pero handoff fue rechazado por {}; no se liberara la reserva y TTL actuara como fallback.",
                    nonNullPlayerId,
                    nonNullBackendName,
                    result
            );
        }
    }

    public void cancelPendingFailover(UUID playerId) {
        UUID nonNullPlayerId = Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        failoverRegistry
                .clearDistributedForDisconnect(nonNullPlayerId)
                .ifPresent(this::releaseBestEffort);
    }

    private CompletionStage<BackendKickFailoverResolution> resolveForType(
            Player player,
            BackendType targetType,
            Set<String> exclusions,
            Component disconnectReason,
            boolean allowLobbyFallback
    ) {
        final CompletionStage<DistributedResolvedTargetAllocation> stage;
        try {
            stage = allocationService.allocate(
                    player,
                    requestIdGenerator.get(),
                    targetType,
                    exclusions
            );
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        if (stage == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "distributed resolved allocation returned null stage"
                    )
            );
        }

        return stage.thenCompose(allocation -> {
            if (allocation == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "distributed resolved allocation returned null"
                        )
                );
            }

            if (allocation.isAllocated()) {
                return registerAllocatedTarget(
                        player.getUniqueId(),
                        allocation,
                        disconnectReason
                );
            }

            if (isTerminalCapacityFailure(allocation)) {
                failoverRegistry.clear(player.getUniqueId());
                return completed(
                        BackendKickFailoverResolution.disconnect(
                                disconnectReason
                        )
                );
            }

            if (!allowLobbyFallback) {
                failoverRegistry.clear(player.getUniqueId());
                return completed(
                        BackendKickFailoverResolution.disconnect(
                                disconnectReason
                        )
                );
            }

            return resolveForType(
                    player,
                    BackendType.LOBBY,
                    exclusions,
                    disconnectReason,
                    false
            );
        });
    }

    private CompletionStage<BackendKickFailoverResolution>
    registerAllocatedTarget(
            UUID playerId,
            DistributedResolvedTargetAllocation allocation,
            Component disconnectReason
    ) {
        BackendCapacityReserveRequest request =
                allocation.requireCapacityRequest();

        if (!failoverRegistry.attachDistributedCapacityRequest(
                playerId,
                request
        )) {
            return releaseAfterLostPendingState(
                    request,
                    disconnectReason
            );
        }

        return completed(
                BackendKickFailoverResolution.redirect(
                        allocation.targetResolution()
                                .resolvedTarget()
                                .orElseThrow()
                )
        );
    }

    private boolean isTerminalCapacityFailure(
            DistributedResolvedTargetAllocation allocation
    ) {
        if (!allocation.isCapacityRejected()) {
            return false;
        }

        BackendCapacityReserveResult.Status status =
                Objects.requireNonNull(
                        allocation.capacityStatus(),
                        "capacity rejection requires status"
                );

        return switch (status) {
            case NO_CAPACITY -> false;
            case REQUEST_ID_CONFLICT,
                    SESSION_NOT_FOUND,
                    NOT_SESSION_OWNER,
                    OCCUPANCY_UNAVAILABLE,
                    COORDINATION_UNAVAILABLE -> true;
            case RESERVED, ALREADY_RESERVED -> throw new IllegalStateException(
                    "successful capacity status cannot be rejected"
            );
        };
    }

    private CompletionStage<BackendKickFailoverResolution>
    releaseAfterLostPendingState(
            BackendCapacityReserveRequest request,
            Component disconnectReason
    ) {
        final CompletionStage<Boolean> releaseStage;
        try {
            releaseStage = releaseService.releaseIfOwned(request);
        } catch (RuntimeException exception) {
            logger.warn(
                    "Se perdio el estado pending del kick failover de {} en {}; no pudo iniciarse release exacto y TTL actuara como fallback.",
                    request.reservation().playerId(),
                    request.reservation().backendName(),
                    exception
            );
            return completed(
                    BackendKickFailoverResolution.disconnect(disconnectReason)
            );
        }

        if (releaseStage == null) {
            logger.warn(
                    "Se perdio el estado pending del kick failover de {} en {}; release devolvio stage nulo y TTL actuara como fallback.",
                    request.reservation().playerId(),
                    request.reservation().backendName()
            );
            return completed(
                    BackendKickFailoverResolution.disconnect(disconnectReason)
            );
        }

        return releaseStage.handle((released, failure) ->
                BackendKickFailoverResolution.disconnect(disconnectReason)
        );
    }

    private void releaseBestEffort(BackendCapacityReserveRequest request) {
        final CompletionStage<Boolean> stage;
        try {
            stage = releaseService.releaseIfOwned(request);
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo iniciar release exacto del kick failover de {} en {}; TTL actuara como fallback.",
                    request.reservation().playerId(),
                    request.reservation().backendName(),
                    exception
            );
            return;
        }

        if (stage == null) {
            logger.warn(
                    "Release exacto del kick failover de {} en {} devolvio stage nulo; TTL actuara como fallback.",
                    request.reservation().playerId(),
                    request.reservation().backendName()
            );
            return;
        }

        stage.whenComplete((released, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(released)) {
                logger.debug(
                        "No se confirmo release exacto del kick failover de {} en {}; TTL cubrira cualquier reserva remanente.",
                        request.reservation().playerId(),
                        request.reservation().backendName()
                );
            }
        });
    }

    private static CompletionStage<BackendKickFailoverResolution> completed(
            BackendKickFailoverResolution resolution
    ) {
        return CompletableFuture.completedFuture(resolution);
    }
}

package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerSessionLeaseBindingRegistry {

    private final Map<UUID, PlayerState> states =
            new HashMap<>();

    private final Map<UUID, List<ConnectionGeneration>>
            generationsByPlayerId =
            new HashMap<>();

    private long lastGeneration;

    public synchronized void begin(
            Player player,
            UUID acquisitionId
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        UUID playerId = nonNullPlayer.getUniqueId();

        PlayerState existing =
                states.getOrDefault(
                        playerId,
                        PlayerState.empty()
                );

        long generation =
                generationFor(
                        playerId,
                        nonNullPlayer
                ).orElseGet(this::nextGeneration);

        rememberGeneration(
                playerId,
                nonNullPlayer,
                generation
        );

        Map<UUID, PendingAcquisition> pending =
                new HashMap<>(
                        existing.pendingAcquisitions()
                );

        PendingAcquisition current =
                pending.get(nonNullAcquisitionId);

        if (current != null
                && current.player() == nonNullPlayer) {
            return;
        }

        pending.put(
                nonNullAcquisitionId,
                new PendingAcquisition(
                        nonNullPlayer,
                        generation,
                        false
                )
        );

        updateState(
                playerId,
                new PlayerState(
                        existing.boundLease(),
                        pending
                )
        );
    }

    public synchronized PlayerSessionLeaseBindingResult bind(
            Player player,
            UUID acquisitionId,
            PlayerSessionLease lease
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        UUID playerId = nonNullPlayer.getUniqueId();

        requireMatchingIdentity(
                playerId,
                nonNullLease
        );

        PlayerState existing = states.get(playerId);

        if (existing == null) {
            return PlayerSessionLeaseBindingResult.STALE;
        }

        PendingAcquisition acquisition =
                existing.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        if (acquisition == null
                || acquisition.player() != nonNullPlayer) {
            return PlayerSessionLeaseBindingResult.STALE;
        }

        Map<UUID, PendingAcquisition> remaining =
                new HashMap<>(
                        existing.pendingAcquisitions()
                );

        remaining.remove(nonNullAcquisitionId);

        if (acquisition.disconnected()) {
            if (existing.boundLease().isPresent()) {
                BoundLease bound =
                        existing.boundLease().orElseThrow();

                boolean sameActiveLeaseOnOtherConnection =
                        !bound.disconnected()
                                && bound.player()
                                != nonNullPlayer
                                && bound.lease()
                                .equals(nonNullLease);

                if (sameActiveLeaseOnOtherConnection) {
                    updateState(
                            playerId,
                            new PlayerState(
                                    existing.boundLease(),
                                    remaining
                            )
                    );

                    return PlayerSessionLeaseBindingResult.STALE;
                }
            }

            Optional<BoundLease> remainingBound =
                    removeDeferredBoundIfSafe(
                            existing.boundLease(),
                            remaining
                    );

            updateState(
                    playerId,
                    new PlayerState(
                            remainingBound,
                            remaining
                    )
            );

            return PlayerSessionLeaseBindingResult.DISCONNECTED;
        }

        long newestOtherGeneration =
                newestGeneration(
                        existing.boundLease(),
                        remaining
                );

        if (acquisition.generation()
                < newestOtherGeneration) {
            updateState(
                    playerId,
                    new PlayerState(
                            existing.boundLease(),
                            remaining
                    )
            );

            return PlayerSessionLeaseBindingResult.STALE;
        }

        Optional<BoundLease> currentBound =
                existing.boundLease();

        if (currentBound.isEmpty()) {
            updateState(
                    playerId,
                    new PlayerState(
                            Optional.of(
                                    new BoundLease(
                                            nonNullPlayer,
                                            acquisition.generation(),
                                            nonNullLease,
                                            false
                                    )
                            ),
                            remaining
                    )
            );

            return PlayerSessionLeaseBindingResult.BOUND;
        }

        BoundLease bound =
                currentBound.orElseThrow();

        if (bound.generation()
                > acquisition.generation()) {
            updateState(
                    playerId,
                    new PlayerState(
                            currentBound,
                            remaining
                    )
            );

            return PlayerSessionLeaseBindingResult.STALE;
        }

        if (bound.lease().equals(nonNullLease)) {
            boolean sameConnection =
                    bound.player() == nonNullPlayer;

            updateState(
                    playerId,
                    new PlayerState(
                            Optional.of(
                                    new BoundLease(
                                            nonNullPlayer,
                                            acquisition.generation(),
                                            nonNullLease,
                                            false
                                    )
                            ),
                            remaining
                    )
            );

            if (sameConnection) {
                return PlayerSessionLeaseBindingResult
                        .ALREADY_BOUND;
            }

            return PlayerSessionLeaseBindingResult.REPLACED;
        }

        long incomingToken =
                nonNullLease.fencingToken();

        long existingToken =
                bound.lease().fencingToken();

        if (incomingToken > existingToken) {
            updateState(
                    playerId,
                    new PlayerState(
                            Optional.of(
                                    new BoundLease(
                                            nonNullPlayer,
                                            acquisition.generation(),
                                            nonNullLease,
                                            false
                                    )
                            ),
                            remaining
                    )
            );

            return PlayerSessionLeaseBindingResult.REPLACED;
        }

        updateState(
                playerId,
                new PlayerState(
                        currentBound,
                        remaining
                )
        );

        if (incomingToken < existingToken) {
            return PlayerSessionLeaseBindingResult.STALE;
        }

        return PlayerSessionLeaseBindingResult.CONFLICT;
    }

    public synchronized Optional<PlayerSessionLease> find(
            Player player
    ) {
        Player nonNullPlayer = requirePlayer(player);

        PlayerState state =
                states.get(
                        nonNullPlayer.getUniqueId()
                );

        if (state == null
                || state.boundLease().isEmpty()) {
            return Optional.empty();
        }

        BoundLease bound =
                state.boundLease().orElseThrow();

        if (bound.player() != nonNullPlayer
                || bound.disconnected()) {
            return Optional.empty();
        }

        return Optional.of(bound.lease());
    }

    public synchronized Optional<PlayerSessionLease>
    removeIfMatches(
            Player player,
            PlayerSessionLease expected
    ) {
        Player nonNullPlayer = requirePlayer(player);

        PlayerSessionLease nonNullExpected =
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                );

        UUID playerId = nonNullPlayer.getUniqueId();

        requireMatchingIdentity(
                playerId,
                nonNullExpected
        );

        PlayerState existing = states.get(playerId);

        if (existing == null
                || existing.boundLease().isEmpty()) {
            return Optional.empty();
        }

        BoundLease bound =
                existing.boundLease().orElseThrow();

        if (bound.player() != nonNullPlayer
                || !bound.lease().equals(nonNullExpected)) {
            return Optional.empty();
        }

        updateState(
                playerId,
                new PlayerState(
                        Optional.empty(),
                        existing.pendingAcquisitions()
                )
        );

        return Optional.of(nonNullExpected);
    }

    public synchronized Optional<PlayerSessionLease>
    removeForDisconnect(
            Player player
    ) {
        Player nonNullPlayer = requirePlayer(player);
        UUID playerId = nonNullPlayer.getUniqueId();

        PlayerState existing = states.get(playerId);

        if (existing == null) {
            return Optional.empty();
        }

        Map<UUID, PendingAcquisition> pending =
                markDisconnected(
                        existing.pendingAcquisitions(),
                        nonNullPlayer
                );

        if (existing.boundLease().isEmpty()) {
            updateState(
                    playerId,
                    new PlayerState(
                            Optional.empty(),
                            pending
                    )
            );

            return Optional.empty();
        }

        BoundLease bound =
                existing.boundLease().orElseThrow();

        if (bound.player() != nonNullPlayer) {
            updateState(
                    playerId,
                    new PlayerState(
                            existing.boundLease(),
                            pending
                    )
            );

            return Optional.empty();
        }

        if (hasNewerPendingAcquisition(
                pending,
                bound.generation()
        )) {
            updateState(
                    playerId,
                    new PlayerState(
                            Optional.of(
                                    bound.asDisconnected()
                            ),
                            pending
                    )
            );

            return Optional.empty();
        }

        updateState(
                playerId,
                new PlayerState(
                        Optional.empty(),
                        pending
                )
        );

        return Optional.of(bound.lease());
    }

    public synchronized Cancellation cancel(
            Player player,
            UUID acquisitionId
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        UUID playerId = nonNullPlayer.getUniqueId();

        PlayerState existing = states.get(playerId);

        if (existing == null) {
            return Cancellation.inactive();
        }

        PendingAcquisition acquisition =
                existing.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        if (acquisition == null
                || acquisition.player() != nonNullPlayer) {
            return Cancellation.inactive();
        }

        long newestGeneration =
                newestGeneration(
                        existing.boundLease(),
                        existing.pendingAcquisitions()
                );

        boolean shouldRespond =
                !acquisition.disconnected()
                        && acquisition.generation()
                        >= newestGeneration;

        Map<UUID, PendingAcquisition> remaining =
                new HashMap<>(
                        existing.pendingAcquisitions()
                );

        remaining.remove(nonNullAcquisitionId);

        Optional<PlayerSessionLease> leaseToRelease =
                Optional.empty();

        Optional<BoundLease> remainingBound =
                existing.boundLease();

        if (remainingBound.isPresent()) {
            BoundLease bound =
                    remainingBound.orElseThrow();

            if (bound.disconnected()
                    && !hasNewerPendingAcquisition(
                    remaining,
                    bound.generation()
            )) {
                leaseToRelease =
                        Optional.of(bound.lease());

                remainingBound = Optional.empty();
            }
        }

        updateState(
                playerId,
                new PlayerState(
                        remainingBound,
                        remaining
                )
        );

        return new Cancellation(
                shouldRespond,
                leaseToRelease
        );
    }

    public synchronized void clear() {
        states.clear();
        generationsByPlayerId.clear();
    }

    private Map<UUID, PendingAcquisition> markDisconnected(
            Map<UUID, PendingAcquisition> pending,
            Player player
    ) {
        Map<UUID, PendingAcquisition> updated =
                new HashMap<>(pending);

        updated.replaceAll(
                (acquisitionId, acquisition) -> {
                    if (acquisition.player() != player) {
                        return acquisition;
                    }

                    return acquisition.asDisconnected();
                }
        );

        return updated;
    }

    private Optional<BoundLease> removeDeferredBoundIfSafe(
            Optional<BoundLease> boundLease,
            Map<UUID, PendingAcquisition> pending
    ) {
        if (boundLease.isEmpty()) {
            return Optional.empty();
        }

        BoundLease bound = boundLease.orElseThrow();

        if (!bound.disconnected()
                || hasNewerPendingAcquisition(
                pending,
                bound.generation()
        )) {
            return boundLease;
        }

        return Optional.empty();
    }

    private boolean hasNewerPendingAcquisition(
            Map<UUID, PendingAcquisition> pending,
            long generation
    ) {
        return pending.values()
                .stream()
                .anyMatch(acquisition ->
                        acquisition.generation() > generation
                );
    }

    private long newestGeneration(
            Optional<BoundLease> boundLease,
            Map<UUID, PendingAcquisition> pending
    ) {
        long newest =
                boundLease
                        .map(BoundLease::generation)
                        .orElse(Long.MIN_VALUE);

        for (PendingAcquisition acquisition
                : pending.values()) {
            newest = Math.max(
                    newest,
                    acquisition.generation()
            );
        }

        return newest;
    }

    private Optional<Long> generationFor(
            UUID playerId,
            Player player
    ) {
        return generationsByPlayerId
                .getOrDefault(
                        playerId,
                        List.of()
                )
                .stream()
                .filter(connection ->
                        connection.player() == player
                )
                .map(ConnectionGeneration::generation)
                .findFirst();
    }

    private void rememberGeneration(
            UUID playerId,
            Player player,
            long generation
    ) {
        List<ConnectionGeneration> connections =
                new ArrayList<>(
                        generationsByPlayerId
                                .getOrDefault(
                                        playerId,
                                        List.of()
                                )
                );

        boolean alreadyKnown =
                connections
                        .stream()
                        .anyMatch(connection ->
                                connection.player() == player
                        );

        if (!alreadyKnown) {
            connections.add(
                    new ConnectionGeneration(
                            player,
                            generation
                    )
            );
        }

        generationsByPlayerId.put(
                playerId,
                List.copyOf(connections)
        );
    }
    private void updateState(
            UUID playerId,
            PlayerState state
    ) {
        if (state.boundLease().isEmpty()
                && state.pendingAcquisitions().isEmpty()) {
            states.remove(playerId);
            generationsByPlayerId.remove(playerId);
            return;
        }

        states.put(playerId, state);
    }

    private long nextGeneration() {
        lastGeneration =
                Math.incrementExact(lastGeneration);

        return lastGeneration;
    }

    private Player requirePlayer(Player player) {
        Player nonNullPlayer = Objects.requireNonNull(
                player,
                "player cannot be null"
        );

        Objects.requireNonNull(
                nonNullPlayer.getUniqueId(),
                "player unique id cannot be null"
        );

        return nonNullPlayer;
    }

    private void requireMatchingIdentity(
            UUID playerId,
            PlayerSessionLease lease
    ) {
        if (!playerId.equals(
                lease.session().playerId()
        )) {
            throw new IllegalArgumentException(
                    "player identity must match lease session"
            );
        }
    }

    public record Cancellation(
            boolean shouldRespond,
            Optional<PlayerSessionLease> leaseToRelease
    ) {

        public Cancellation {
            leaseToRelease = Objects.requireNonNull(
                    leaseToRelease,
                    "leaseToRelease cannot be null"
            );
        }

        private static Cancellation inactive() {
            return new Cancellation(
                    false,
                    Optional.empty()
            );
        }
    }

    private record PlayerState(
            Optional<BoundLease> boundLease,
            Map<UUID, PendingAcquisition> pendingAcquisitions
    ) {

        private PlayerState {
            boundLease = Objects.requireNonNull(
                    boundLease,
                    "boundLease cannot be null"
            );

            pendingAcquisitions = Map.copyOf(
                    Objects.requireNonNull(
                            pendingAcquisitions,
                            "pendingAcquisitions cannot be null"
                    )
            );
        }

        private static PlayerState empty() {
            return new PlayerState(
                    Optional.empty(),
                    Map.of()
            );
        }


    }

    private record ConnectionGeneration(
            Player player,
            long generation
    ) {

        private ConnectionGeneration {
            Objects.requireNonNull(
                    player,
                    "player cannot be null"
            );

            if (generation <= 0) {
                throw new IllegalArgumentException(
                        "generation must be greater than zero"
                );
            }
        }
    }
    private record BoundLease(
            Player player,
            long generation,
            PlayerSessionLease lease,
            boolean disconnected
    ) {

        private BoundLease {
            Objects.requireNonNull(
                    player,
                    "player cannot be null"
            );

            Objects.requireNonNull(
                    lease,
                    "lease cannot be null"
            );

            if (generation <= 0) {
                throw new IllegalArgumentException(
                        "generation must be greater than zero"
                );
            }
        }

        private BoundLease asDisconnected() {
            return new BoundLease(
                    player,
                    generation,
                    lease,
                    true
            );
        }
    }

    private record PendingAcquisition(
            Player player,
            long generation,
            boolean disconnected
    ) {

        private PendingAcquisition {
            Objects.requireNonNull(
                    player,
                    "player cannot be null"
            );

            if (generation <= 0) {
                throw new IllegalArgumentException(
                        "generation must be greater than zero"
                );
            }
        }

        private PendingAcquisition asDisconnected() {
            return new PendingAcquisition(
                    player,
                    generation,
                    true
            );
        }
    }
}

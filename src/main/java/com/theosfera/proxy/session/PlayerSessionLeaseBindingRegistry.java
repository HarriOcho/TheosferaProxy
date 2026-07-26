package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PlayerSessionLeaseBindingRegistry {

    private final Map<UUID, PlayerState> states =
            new HashMap<>();

    private final Map<UUID, List<ConnectionGeneration>>
            generationsByPlayerId =
            new HashMap<>();

    private final Map<PlayerSessionLease, CompletableFuture<Boolean>>
            pendingReleases =
            new HashMap<>();

    private long lastGeneration;
    private long lastAttempt;

    public synchronized long begin(
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
            return current.attemptId();
        }

        long attemptId = nextAttempt();

        pending.put(
                nonNullAcquisitionId,
                new PendingAcquisition(
                        nonNullPlayer,
                        generation,
                        attemptId,
                        false,
                        false,
                        0L,
                        null
                )
        );

        updateState(
                playerId,
                new PlayerState(
                        existing.boundLease(),
                        pending
                )
        );

        return attemptId;
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

        CompletableFuture<Boolean> pendingRelease =
                pendingReleases.get(nonNullLease);

        if (pendingRelease != null) {
            PendingAcquisition waiting =
                    acquisition.waitingForRelease(
                            nonNullLease,
                            pendingRelease
                    );

            PendingRelease waitingRelease =
                    waiting.pendingRelease();

            boolean releaseCanBeAwaited =
                    waitingRelease != null
                            && waitingRelease
                            .lease()
                            .owner()
                            .equals(nonNullLease.owner());

            if (releaseCanBeAwaited) {
                remaining.put(
                        nonNullAcquisitionId,
                        waiting
                );

                updateState(
                        playerId,
                        new PlayerState(
                                existing.boundLease(),
                                remaining
                        )
                );

                return PlayerSessionLeaseBindingResult
                        .RELEASE_PENDING;
            }
        }

        PendingRelease acquisitionRelease =
                acquisition.pendingRelease();

        boolean releaseMatchesLeaseOwner =
                acquisitionRelease == null
                        || acquisitionRelease
                        .lease()
                        .owner()
                        .equals(nonNullLease.owner());

        if (acquisitionRelease != null
                && releaseMatchesLeaseOwner
                && nonNullLease.fencingToken()
                <= acquisition
                .minimumFencingTokenExclusive()) {
            updateState(
                    playerId,
                    new PlayerState(
                            existing.boundLease(),
                            remaining
                    )
            );

            return PlayerSessionLeaseBindingResult
                    .RELEASE_PENDING;
        }

        remaining.remove(nonNullAcquisitionId);

        if (releaseMatchesLeaseOwner
                && nonNullLease.fencingToken()
                <= acquisition
                .minimumFencingTokenExclusive()) {
            updateState(
                    playerId,
                    new PlayerState(
                            existing.boundLease(),
                            remaining
                    )
            );

            return PlayerSessionLeaseBindingResult.STALE;
        }

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

    public synchronized boolean reserveReleaseIfUnbound(
            PlayerSessionLease lease
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        UUID playerId =
                nonNullLease.session().playerId();

        PlayerState state = states.get(playerId);

        if (state != null
                && state.boundLease().isPresent()) {
            BoundLease bound =
                    state.boundLease().orElseThrow();

            boolean protectedByActiveBinding =
                    !bound.disconnected()
                            && bound.lease()
                            .equals(nonNullLease);

            if (protectedByActiveBinding) {
                return false;
            }
        }

        if (pendingReleases.containsKey(
                nonNullLease
        )) {
            return false;
        }

        pendingReleases.put(
                nonNullLease,
                new CompletableFuture<>()
        );

        return true;
    }

    public synchronized boolean claimAcquisitionResult(
            Player player,
            UUID acquisitionId,
            long expectedAttemptId
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        UUID playerId =
                nonNullPlayer.getUniqueId();

        PlayerState state = states.get(playerId);

        if (state == null) {
            return false;
        }

        PendingAcquisition acquisition =
                state.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        if (acquisition == null
                || acquisition.player() != nonNullPlayer
                || acquisition.attemptId() != expectedAttemptId
                || acquisition.acquisitionResultClaimed()) {
            return false;
        }

        Map<UUID, PendingAcquisition> pending =
                new HashMap<>(
                        state.pendingAcquisitions()
                );

        pending.put(
                nonNullAcquisitionId,
                acquisition.withAcquisitionResultClaimed()
        );

        updateState(
                playerId,
                new PlayerState(
                        state.boundLease(),
                        pending
                )
        );

        return true;
    }
    public synchronized Optional<CompletionStage<Boolean>>
    awaitPendingRelease(
            Player player,
            UUID acquisitionId,
            ProxyInstanceIdentity expectedOwner
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );
        ProxyInstanceIdentity nonNullExpectedOwner =
                Objects.requireNonNull(
                        expectedOwner,
                        "expectedOwner cannot be null"
                );


        UUID playerId =
                nonNullPlayer.getUniqueId();

        PlayerState state = states.get(playerId);

        if (state == null) {
            return Optional.empty();
        }

        PendingAcquisition acquisition =
                state.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        if (acquisition == null
                || acquisition.player()
                != nonNullPlayer) {
            return Optional.empty();
        }

        PendingRelease existingRelease =
                acquisition.pendingRelease();

        if (existingRelease != null) {
            if (!existingRelease
                    .lease()
                    .owner()
                    .equals(nonNullExpectedOwner)) {
                return Optional.empty();
            }

            return Optional.of(
                    existingRelease.completion()
            );
        }

        PlayerSessionLease pendingLease = null;
        CompletableFuture<Boolean> completion = null;

        for (Map.Entry<PlayerSessionLease,
                CompletableFuture<Boolean>> entry
                : pendingReleases.entrySet()) {
            PlayerSessionLease candidate =
                    entry.getKey();

            if (!playerId.equals(
                    candidate.session().playerId()
            )) {
                continue;
            }

            if (!candidate.owner().equals(
                    nonNullExpectedOwner
            )) {
                continue;
            }

            if (candidate.fencingToken()
                    <= acquisition
                    .minimumFencingTokenExclusive()) {
                continue;
            }

            if (pendingLease == null
                    || candidate.fencingToken()
                    > pendingLease.fencingToken()) {
                pendingLease = candidate;
                completion = entry.getValue();
            }
        }

        if (pendingLease == null
                || completion == null) {
            return Optional.empty();
        }

        PendingAcquisition waiting =
                acquisition.waitingForRelease(
                        pendingLease,
                        completion
                );

        Map<UUID, PendingAcquisition> pending =
                new HashMap<>(
                        state.pendingAcquisitions()
                );

        pending.put(
                nonNullAcquisitionId,
                waiting
        );

        updateState(
                playerId,
                new PlayerState(
                        state.boundLease(),
                        pending
                )
        );

        return Optional.of(completion);
    }

    public synchronized OptionalLong
    claimReleaseCompletionAndBeginRetry(
            Player player,
            UUID acquisitionId,
            long expectedAttemptId,
            CompletionStage<Boolean> expectedCompletion
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        CompletionStage<Boolean> nonNullExpected =
                Objects.requireNonNull(
                        expectedCompletion,
                        "expectedCompletion cannot be null"
                );

        UUID playerId =
                nonNullPlayer.getUniqueId();

        PlayerState state = states.get(playerId);

        if (state == null) {
            return OptionalLong.empty();
        }

        PendingAcquisition acquisition =
                state.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        PendingRelease pendingRelease =
                acquisition == null
                        ? null
                        : acquisition.pendingRelease();

        if (acquisition == null
                || acquisition.player() != nonNullPlayer
                || acquisition.attemptId() != expectedAttemptId
                || !acquisition.acquisitionResultClaimed()
                || pendingRelease == null
                || pendingRelease.completion()
                != nonNullExpected) {
            return OptionalLong.empty();
        }

        long retryAttemptId = nextAttempt();

        Map<UUID, PendingAcquisition> pending =
                new HashMap<>(
                        state.pendingAcquisitions()
                );

        pending.put(
                nonNullAcquisitionId,
                acquisition.beginRetry(retryAttemptId)
        );

        updateState(
                playerId,
                new PlayerState(
                        state.boundLease(),
                        pending
                )
        );

        return OptionalLong.of(retryAttemptId);
    }
    public synchronized boolean claimReleaseCompletion(
            Player player,
            UUID acquisitionId,
            CompletionStage<Boolean> expectedCompletion
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        CompletionStage<Boolean> nonNullExpected =
                Objects.requireNonNull(
                        expectedCompletion,
                        "expectedCompletion cannot be null"
                );

        UUID playerId =
                nonNullPlayer.getUniqueId();

        PlayerState state = states.get(playerId);

        if (state == null) {
            return false;
        }

        PendingAcquisition acquisition =
                state.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        PendingRelease pendingRelease =
                acquisition == null
                        ? null
                        : acquisition.pendingRelease();

        if (acquisition == null
                || acquisition.player()
                != nonNullPlayer
                || pendingRelease == null
                || pendingRelease.completion()
                != nonNullExpected) {
            return false;
        }

        Map<UUID, PendingAcquisition> pending =
                new HashMap<>(
                        state.pendingAcquisitions()
                );

        pending.put(
                nonNullAcquisitionId,
                acquisition.withoutPendingRelease()
        );

        updateState(
                playerId,
                new PlayerState(
                        state.boundLease(),
                        pending
                )
        );

        return true;
    }

    public void completeRelease(
            PlayerSessionLease lease,
            boolean released
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        CompletableFuture<Boolean> completion;

        synchronized (this) {
            completion =
                    pendingReleases.remove(
                            nonNullLease
                    );

            if (completion != null) {
                attachReleaseCompletionToPendingAcquisitions(
                        nonNullLease,
                        completion
                );
            }
        }

        if (completion != null) {
            completion.complete(released);
        }
    }

    public void failRelease(
            PlayerSessionLease lease,
            Throwable failure
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        Throwable nonNullFailure =
                Objects.requireNonNull(
                        failure,
                        "failure cannot be null"
                );

        CompletableFuture<Boolean> completion;

        synchronized (this) {
            completion =
                    pendingReleases.remove(
                            nonNullLease
                    );

            if (completion != null) {
                attachReleaseCompletionToPendingAcquisitions(
                        nonNullLease,
                        completion
                );
            }
        }

        if (completion != null) {
            completion.completeExceptionally(
                    nonNullFailure
            );
        }
    }

    private void attachReleaseCompletionToPendingAcquisitions(
            PlayerSessionLease lease,
            CompletionStage<Boolean> completion
    ) {
        UUID playerId =
                lease.session().playerId();

        PlayerState state = states.get(playerId);

        if (state == null
                || state.pendingAcquisitions().isEmpty()) {
            return;
        }

        Map<UUID, PendingAcquisition> pending =
                new HashMap<>(
                        state.pendingAcquisitions()
                );

        pending.replaceAll(
                (acquisitionId, acquisition) ->
                        acquisition.waitingForRelease(
                                lease,
                                completion
                        )
        );

        updateState(
                playerId,
                new PlayerState(
                        state.boundLease(),
                        pending
                )
        );
    }
    public synchronized void clear() {
        states.clear();
        generationsByPlayerId.clear();
        pendingReleases.clear();
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

    private long nextAttempt() {
        lastAttempt =
                Math.incrementExact(lastAttempt);

        return lastAttempt;
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

    private record PendingRelease(
            PlayerSessionLease lease,
            CompletionStage<Boolean> completion
    ) {

        private PendingRelease {
            Objects.requireNonNull(
                    lease,
                    "lease cannot be null"
            );

            Objects.requireNonNull(
                    completion,
                    "completion cannot be null"
            );
        }
    }

    private record PendingAcquisition(
            Player player,
            long generation,
            long attemptId,
            boolean acquisitionResultClaimed,
            boolean disconnected,
            long minimumFencingTokenExclusive,
            PendingRelease pendingRelease
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

            if (attemptId <= 0) {
                throw new IllegalArgumentException(
                        "attemptId must be greater than zero"
                );
            }

            if (minimumFencingTokenExclusive < 0) {
                throw new IllegalArgumentException(
                        "minimum fencing token cannot be negative"
                );
            }
        }

        private PendingAcquisition
        withAcquisitionResultClaimed() {
            return new PendingAcquisition(
                    player,
                    generation,
                    attemptId,
                    true,
                    disconnected,
                    minimumFencingTokenExclusive,
                    pendingRelease
            );
        }

        private PendingAcquisition beginRetry(
                long retryAttemptId
        ) {
            return new PendingAcquisition(
                    player,
                    generation,
                    retryAttemptId,
                    false,
                    disconnected,
                    minimumFencingTokenExclusive,
                    null
            );
        }

        private PendingAcquisition asDisconnected() {
            return new PendingAcquisition(
                    player,
                    generation,
                    attemptId,
                    acquisitionResultClaimed,
                    true,
                    minimumFencingTokenExclusive,
                    pendingRelease
            );
        }

        private PendingAcquisition waitingForRelease(
                PlayerSessionLease lease,
                CompletionStage<Boolean> completion
        ) {
            PlayerSessionLease nonNullLease =
                    Objects.requireNonNull(
                            lease,
                            "lease cannot be null"
                    );

            CompletionStage<Boolean> nonNullCompletion =
                    Objects.requireNonNull(
                            completion,
                            "completion cannot be null"
                    );

            if (nonNullLease.fencingToken()
                    <= minimumFencingTokenExclusive) {
                return this;
            }

            long nextMinimumFencingTokenExclusive =
                    Math.max(
                            minimumFencingTokenExclusive,
                            nonNullLease.fencingToken()
                    );

            if (pendingRelease != null) {
                if (!pendingRelease
                        .lease()
                        .owner()
                        .equals(nonNullLease.owner())) {
                    return this;
                }

                return new PendingAcquisition(
                        player,
                        generation,
                        attemptId,
                        acquisitionResultClaimed,
                        disconnected,
                        nextMinimumFencingTokenExclusive,
                        pendingRelease
                );
            }

            return new PendingAcquisition(
                    player,
                    generation,
                    attemptId,
                    acquisitionResultClaimed,
                    disconnected,
                    nextMinimumFencingTokenExclusive,
                    new PendingRelease(
                            nonNullLease,
                            nonNullCompletion
                    )
            );
        }

        private PendingAcquisition withoutPendingRelease() {
            return new PendingAcquisition(
                    player,
                    generation,
                    attemptId,
                    acquisitionResultClaimed,
                    disconnected,
                    minimumFencingTokenExclusive,
                    null
            );
        }
    }
}

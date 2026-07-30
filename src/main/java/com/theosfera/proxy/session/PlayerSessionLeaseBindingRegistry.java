package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

public final class PlayerSessionLeaseBindingRegistry {

    private static final int DEFAULT_REQUEST_CAPACITY = 4_096;

    private static final long
            DEFAULT_TERMINAL_REPLAY_WINDOW =
            60_000_000_000L;

    private final Map<UUID, PlayerState> states =
            new HashMap<>();

    private final Map<UUID, List<ConnectionGeneration>>
            generationsByPlayerId =
            new HashMap<>();

    private final Map<PlayerSessionLease, TrackedRelease>
            pendingReleases =
            new HashMap<>();

    private final LinkedHashMap<ReleaseQuarantineKey, ReleaseQuarantine>
            releaseQuarantines =
            new LinkedHashMap<>();

    private final LinkedHashMap<UUID, FencingFloor>
            releaseFencingFloors =
            new LinkedHashMap<>();
    private boolean unknownFencingFloorAdmissionsClosed;

    private final Map<UUID, ActiveRequest> activeRequests =
            new HashMap<>();

    private final Map<UUID, TerminalRequest> terminalRequests =
            new LinkedHashMap<>();

    private final LongSupplier monotonicTime;
    private final int requestCapacity;
    private final long terminalReplayWindow;
    private final long releaseQuarantineTtl;
    private final int releaseQuarantineCapacity;
    private final int fencingFloorCapacity;

    private long lastGeneration;
    private long lastAttempt;

    public PlayerSessionLeaseBindingRegistry() {
        this(
                System::nanoTime,
                DEFAULT_REQUEST_CAPACITY,
                DEFAULT_TERMINAL_REPLAY_WINDOW,
                DEFAULT_TERMINAL_REPLAY_WINDOW,
                DEFAULT_REQUEST_CAPACITY,
                DEFAULT_REQUEST_CAPACITY
        );
    }

    PlayerSessionLeaseBindingRegistry(
            LongSupplier monotonicTime,
            int requestCapacity,
            long terminalReplayWindow
    ) {
        this(
                monotonicTime,
                requestCapacity,
                terminalReplayWindow,
                DEFAULT_TERMINAL_REPLAY_WINDOW,
                requestCapacity,
                requestCapacity
        );
    }

    PlayerSessionLeaseBindingRegistry(
            LongSupplier monotonicTime,
            int requestCapacity,
            long terminalReplayWindow,
            long releaseQuarantineTtl,
            int releaseQuarantineCapacity,
            int fencingFloorCapacity
    ) {
        this.monotonicTime =
                Objects.requireNonNull(
                        monotonicTime,
                        "monotonicTime cannot be null"
                );

        if (requestCapacity <= 0) {
            throw new IllegalArgumentException(
                    "requestCapacity must be greater than zero"
            );
        }

        if (terminalReplayWindow <= 0) {
            throw new IllegalArgumentException(
                    "terminalReplayWindow "
                            + "must be greater than zero"
            );
        }

        if (releaseQuarantineTtl <= 0) {
            throw new IllegalArgumentException(
                    "releaseQuarantineTtl "
                            + "must be greater than zero"
            );
        }

        if (releaseQuarantineCapacity <= 0) {
            throw new IllegalArgumentException(
                    "releaseQuarantineCapacity "
                            + "must be greater than zero"
            );
        }

        if (fencingFloorCapacity <= 0) {
            throw new IllegalArgumentException(
                    "fencingFloorCapacity "
                            + "must be greater than zero"
            );
        }

        this.requestCapacity = requestCapacity;
        this.terminalReplayWindow =
                terminalReplayWindow;
        this.releaseQuarantineTtl =
                releaseQuarantineTtl;
        this.releaseQuarantineCapacity =
                releaseQuarantineCapacity;
        this.fencingFloorCapacity =
                fencingFloorCapacity;
    }

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

    public synchronized BeginResult beginTracked(
            Player player,
            UUID acquisitionId,
            AuthenticatedPlayerSession session
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        AuthenticatedPlayerSession nonNullSession =
                Objects.requireNonNull(
                        session,
                        "session cannot be null"
                );

        UUID playerId = nonNullPlayer.getUniqueId();

        if (!playerId.equals(nonNullSession.playerId())) {
            throw new IllegalArgumentException(
                    "player identity must match session"
            );
        }

        purgeExpiredTerminalRequests();

        TerminalRequest terminal =
                terminalRequests.get(nonNullAcquisitionId);

        if (terminal != null) {
            if (terminal.session().equals(nonNullSession)) {
                if (terminal.acknowledgement().successful()
                        && !hasLiveSuccessfulReplayBinding(
                                nonNullPlayer,
                                terminal
                        )) {
                    return BeginResult.conflict(
                            Optional.empty()
                    );
                }

                return BeginResult.completedReplay(
                        terminal.acknowledgement()
                );
            }

            return BeginResult.conflict(Optional.empty());
        }

        ActiveRequest active =
                activeRequests.get(nonNullAcquisitionId);

        if (active != null) {
            UUID activePlayerId =
                    active.player().getUniqueId();

            PlayerState activeState =
                    states.get(activePlayerId);

            PendingAcquisition activeAcquisition =
                    activeState == null
                            ? null
                            : activeState
                            .pendingAcquisitions()
                            .get(nonNullAcquisitionId);

            boolean stillActive =
                    activeAcquisition != null
                            && activeAcquisition.player()
                            == active.player();

            if (!stillActive) {
                activeRequests.remove(nonNullAcquisitionId);
            } else if (active.player() == nonNullPlayer
                    && active.session().equals(nonNullSession)) {
                return BeginResult.pendingReplay();
            } else {
                return BeginResult.conflict(
                        Optional.empty()
                );
            }
        }

        if (trackedRequestCount() >= requestCapacity) {
            return BeginResult.capacityExhausted();
        }

        long attemptId =
                begin(
                        nonNullPlayer,
                        nonNullAcquisitionId
                );

        activeRequests.put(
                nonNullAcquisitionId,
                new ActiveRequest(
                        nonNullPlayer,
                        nonNullSession,
                        attemptId
                )
        );

        return BeginResult.proceed(attemptId);
    }
    public synchronized PlayerSessionLeaseBindingResult bind(
            Player player,
            UUID acquisitionId,
            long expectedAttemptId,
            AuthenticatedPlayerSession expectedSession,
            PlayerSessionLease lease,
            TerminalAcknowledgement successfulAcknowledgement,
            TerminalAcknowledgement conflictAcknowledgement
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        if (expectedAttemptId <= 0) {
            throw new IllegalArgumentException(
                    "expectedAttemptId must be greater than zero"
            );
        }

        AuthenticatedPlayerSession nonNullExpectedSession =
                Objects.requireNonNull(
                        expectedSession,
                        "expectedSession cannot be null"
                );

        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        TerminalAcknowledgement
                nonNullSuccessfulAcknowledgement =
                Objects.requireNonNull(
                        successfulAcknowledgement,
                        "successfulAcknowledgement cannot be null"
                );

        TerminalAcknowledgement
                nonNullConflictAcknowledgement =
                Objects.requireNonNull(
                        conflictAcknowledgement,
                        "conflictAcknowledgement cannot be null"
                );

        UUID playerId = nonNullPlayer.getUniqueId();

        if (!playerId.equals(
                nonNullExpectedSession.playerId()
        )) {
            throw new IllegalArgumentException(
                    "player identity must match expected session"
            );
        }

        requireMatchingIdentity(
                playerId,
                nonNullLease
        );

        if (!nonNullLease.session()
                .equals(nonNullExpectedSession)) {
            throw new IllegalArgumentException(
                    "lease session must match expected session"
            );
        }

        return bind(
                nonNullPlayer,
                nonNullAcquisitionId,
                nonNullLease,
                nonNullSuccessfulAcknowledgement,
                nonNullConflictAcknowledgement,
                expectedAttemptId,
                nonNullExpectedSession
        );
    }

    private PlayerSessionLeaseBindingResult bind(
            Player nonNullPlayer,
            UUID nonNullAcquisitionId,
            PlayerSessionLease nonNullLease,
            TerminalAcknowledgement successfulAcknowledgement,
            TerminalAcknowledgement conflictAcknowledgement,
            long expectedAttemptId,
            AuthenticatedPlayerSession expectedSession
    ) {
        TerminalAcknowledgement nonNullSuccessfulAcknowledgement =
                Objects.requireNonNull(
                        successfulAcknowledgement,
                        "successfulAcknowledgement cannot be null"
                );

        TerminalAcknowledgement nonNullConflictAcknowledgement =
                Objects.requireNonNull(
                        conflictAcknowledgement,
                        "conflictAcknowledgement cannot be null"
                );

        UUID playerId = nonNullPlayer.getUniqueId();

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

        ActiveRequest activeRequest =
                activeRequests.get(
                        nonNullAcquisitionId
                );

        if (acquisition.attemptId() != expectedAttemptId
                || !acquisition.acquisitionResultClaimed()
                || activeRequest == null
                || activeRequest.player() != nonNullPlayer
                || activeRequest.attemptId()
                != expectedAttemptId
                || !activeRequest.session()
                .equals(expectedSession)) {
            return PlayerSessionLeaseBindingResult.STALE;
        }

        Map<UUID, PendingAcquisition> remaining =
                new HashMap<>(
                        existing.pendingAcquisitions()
                );

        TrackedRelease pendingRelease =
                pendingReleases.get(nonNullLease);

        if (pendingRelease != null) {
            PendingAcquisition waiting =
                    acquisition.waitingForRelease(
                            nonNullLease,
                            pendingRelease.completion()
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

        if (unknownFencingFloorAdmissionsClosed
                && !releaseFencingFloors.containsKey(playerId)) {
            updateState(
                    playerId,
                    new PlayerState(
                            existing.boundLease(),
                            remaining
                    )
            );

            discardActiveRequestWithoutReplay(
                    nonNullAcquisitionId,
                    nonNullPlayer,
                    expectedAttemptId,
                    expectedSession
            );

            return PlayerSessionLeaseBindingResult.STALE;
        }

        long fencingFloor =
                fencingFloorFor(playerId);

        if (releaseMatchesLeaseOwner
                && nonNullLease.fencingToken()
                <= Math.max(
                acquisition.minimumFencingTokenExclusive(),
                fencingFloor
        )) {
            updateState(
                    playerId,
                    new PlayerState(
                            existing.boundLease(),
                            remaining
                    )
            );

            discardActiveRequestWithoutReplay(
                    nonNullAcquisitionId,
                    nonNullPlayer,
                    expectedAttemptId,
                    expectedSession
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

                    discardActiveRequestWithoutReplay(
                            nonNullAcquisitionId,
                            nonNullPlayer,
                            expectedAttemptId,
                            expectedSession
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

            discardActiveRequestWithoutReplay(
                    nonNullAcquisitionId,
                    nonNullPlayer,
                    expectedAttemptId,
                    expectedSession
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

            discardActiveRequestWithoutReplay(
                    nonNullAcquisitionId,
                    nonNullPlayer,
                    expectedAttemptId,
                    expectedSession
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

            completeSuccessfulBinding(
                    nonNullAcquisitionId,
                    nonNullPlayer,
                    nonNullLease,
                    nonNullSuccessfulAcknowledgement
            );

            clearReleaseQuarantineIfSuperseded(
                    playerId,
                    nonNullLease
            );

            return PlayerSessionLeaseBindingResult.BOUND;
        }

        BoundLease bound =
                currentBound.orElseThrow();

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
                completeSuccessfulBinding(
                        nonNullAcquisitionId,
                        nonNullPlayer,
                        nonNullLease,
                        nonNullSuccessfulAcknowledgement
                );

                clearReleaseQuarantineIfSuperseded(
                        playerId,
                        nonNullLease
                );

                return PlayerSessionLeaseBindingResult
                        .ALREADY_BOUND;
            }

            completeSuccessfulBinding(
                    nonNullAcquisitionId,
                    nonNullPlayer,
                    nonNullLease,
                    nonNullSuccessfulAcknowledgement
            );

            clearReleaseQuarantineIfSuperseded(
                    playerId,
                    nonNullLease
            );

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

            completeSuccessfulBinding(
                    nonNullAcquisitionId,
                    nonNullPlayer,
                    nonNullLease,
                    nonNullSuccessfulAcknowledgement
            );

            clearReleaseQuarantineIfSuperseded(
                    playerId,
                    nonNullLease
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
            discardActiveRequestWithoutReplay(
                    nonNullAcquisitionId,
                    nonNullPlayer,
                    expectedAttemptId,
                    expectedSession
            );

            return PlayerSessionLeaseBindingResult.STALE;
        }

        completeActiveRequest(
                nonNullAcquisitionId,
                nonNullConflictAcknowledgement
        );

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

    private Cancellation cancel(
            Player nonNullPlayer,
            UUID nonNullAcquisitionId,
            TerminalAcknowledgement acknowledgement
    ) {
        TerminalAcknowledgement nonNullAcknowledgement =
                Objects.requireNonNull(
                        acknowledgement,
                        "acknowledgement cannot be null"
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

        completeActiveRequest(
                nonNullAcquisitionId,
                nonNullAcknowledgement
        );

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

        if (unknownFencingFloorAdmissionsClosed
                && !releaseFencingFloors.containsKey(playerId)) {
            return false;
        }

        if (nonNullLease.fencingToken()
                <= fencingFloorFor(playerId)) {
            return false;
        }

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

        if (pendingReleases.containsKey(nonNullLease)) {
            return false;
        }

        pendingReleases.put(
                nonNullLease,
                TrackedRelease.awaitable(
                        nonNullLease,
                        new CompletableFuture<>(),
                        null
                )
        );

        return true;
    }

    public synchronized boolean attachReleaseCompletion(
            PlayerSessionLease lease,
            CompletionStage<Boolean> externalCompletion
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        CompletionStage<Boolean> nonNullExternal =
                Objects.requireNonNull(
                        externalCompletion,
                        "externalCompletion cannot be null"
                );

        TrackedRelease trackedRelease =
                pendingReleases.get(nonNullLease);

        if (trackedRelease != null) {
            CompletionStage<Boolean> existingExternal =
                    trackedRelease.externalCompletion();

            if (existingExternal == nonNullExternal) {
                return true;
            }

            if (existingExternal != null) {
                return false;
            }

            pendingReleases.put(
                    nonNullLease,
                    trackedRelease.withExternalCompletion(
                            nonNullExternal
                    )
            );

            return true;
        }

        ReleaseQuarantineKey quarantineKey =
                findUnattachedReleaseQuarantineKey(
                        nonNullLease
                );

        if (quarantineKey == null) {
            return false;
        }

        ReleaseQuarantine quarantine =
                releaseQuarantines.get(quarantineKey);

        CompletionStage<Boolean> existingExternal =
                quarantine.externalCompletion();

        if (existingExternal == nonNullExternal) {
            return true;
        }

        if (existingExternal != null) {
            return false;
        }

        releaseQuarantines.replace(
                quarantineKey,
                quarantine.withExternalCompletion(nonNullExternal)
        );

        return true;
    }

    public synchronized boolean claimReleaseTimeout(
            PlayerSessionLease lease,
            CompletionStage<Boolean> expectedExternalCompletion
    ) {
        return claimReleaseTimeoutWithEvictions(
                lease,
                expectedExternalCompletion
        ).claimed();
    }

    public synchronized ReleaseTimeoutClaim
    claimReleaseTimeoutWithEvictions(
            PlayerSessionLease lease,
            CompletionStage<Boolean> expectedExternalCompletion
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        CompletionStage<Boolean> nonNullExpected =
                Objects.requireNonNull(
                        expectedExternalCompletion,
                        "expectedExternalCompletion cannot be null"
                );

        TrackedRelease trackedRelease =
                pendingReleases.get(nonNullLease);

        if (trackedRelease == null
                || !trackedRelease.lease().equals(nonNullLease)
                || trackedRelease.externalCompletion()
                != nonNullExpected) {
            return ReleaseTimeoutClaim.notClaimed();
        }

        pendingReleases.remove(nonNullLease);
        List<QuarantinedReleaseTimeout> evicted =
                quarantineRelease(
                nonNullLease,
                trackedRelease.completion(),
                trackedRelease.externalCompletion()
        );

        return ReleaseTimeoutClaim.claimed(evicted);
    }

    public boolean failReleaseTimeoutScheduling(
            PlayerSessionLease lease,
            CompletionStage<Boolean> expectedExternalCompletion
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        CompletionStage<Boolean> nonNullExpected =
                Objects.requireNonNull(
                        expectedExternalCompletion,
                        "expectedExternalCompletion cannot be null"
                );

        CompletableFuture<Boolean> completion;
        synchronized (this) {
            TrackedRelease trackedRelease =
                    pendingReleases.get(nonNullLease);

            if (trackedRelease == null
                    || !trackedRelease.lease().equals(nonNullLease)
                    || trackedRelease.externalCompletion()
                    != nonNullExpected) {
                return false;
            }

            pendingReleases.remove(nonNullLease);
            completion = trackedRelease.completion();

            recordFencingFloor(
                    nonNullLease.session().playerId(),
                    nonNullLease.fencingToken()
            );

            terminalizeExactWaiters(
                    nonNullLease.session().playerId(),
                    nonNullLease,
                    completion,
                    failClosedAcknowledgement()
            );
        }

        completion.complete(false);
        return true;
    }

    public synchronized boolean claimReleaseQuarantineRetentionTimeout(
            PlayerSessionLease lease,
            CompletionStage<Boolean> expectedExternalCompletion
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        CompletionStage<Boolean> nonNullExpected =
                Objects.requireNonNull(
                        expectedExternalCompletion,
                        "expectedExternalCompletion cannot be null"
                );

        ReleaseQuarantineKey key =
                findReleaseQuarantineKey(
                        nonNullLease,
                        nonNullExpected
                );

        if (key == null) {
            return false;
        }

        removeExactQuarantineFailClosed(
                nonNullLease,
                key.completion(),
                failClosedAcknowledgement()
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

    public synchronized Cancellation claimAcquisitionTimeout(
            Player player,
            UUID acquisitionId,
            long expectedAttemptId,
            AuthenticatedPlayerSession expectedSession,
            TerminalAcknowledgement acknowledgement
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        if (expectedAttemptId <= 0) {
            throw new IllegalArgumentException(
                    "expectedAttemptId must be greater than zero"
            );
        }

        AuthenticatedPlayerSession nonNullExpectedSession =
                Objects.requireNonNull(
                        expectedSession,
                        "expectedSession cannot be null"
                );

        TerminalAcknowledgement nonNullAcknowledgement =
                Objects.requireNonNull(
                        acknowledgement,
                        "acknowledgement cannot be null"
                );

        UUID playerId =
                nonNullPlayer.getUniqueId();

        PlayerState state = states.get(playerId);

        if (state == null) {
            return Cancellation.inactive();
        }

        PendingAcquisition acquisition =
                state.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        ActiveRequest activeRequest =
                activeRequests.get(
                        nonNullAcquisitionId
                );

        if (acquisition == null
                || acquisition.player() != nonNullPlayer
                || acquisition.attemptId() != expectedAttemptId
                || acquisition.acquisitionResultClaimed()
                || activeRequest == null
                || activeRequest.player() != nonNullPlayer
                || activeRequest.attemptId()
                != expectedAttemptId
                || !activeRequest.session()
                .equals(nonNullExpectedSession)) {
            return Cancellation.inactive();
        }

        return cancel(
                nonNullPlayer,
                nonNullAcquisitionId,
                nonNullAcknowledgement
        );
    }

    public synchronized Cancellation completeTerminalRequest(
            Player player,
            UUID acquisitionId,
            long expectedAttemptId,
            AuthenticatedPlayerSession expectedSession,
            TerminalAcknowledgement acknowledgement
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        if (expectedAttemptId <= 0) {
            throw new IllegalArgumentException(
                    "expectedAttemptId must be greater than zero"
            );
        }

        AuthenticatedPlayerSession nonNullExpectedSession =
                Objects.requireNonNull(
                        expectedSession,
                        "expectedSession cannot be null"
                );

        TerminalAcknowledgement nonNullAcknowledgement =
                Objects.requireNonNull(
                        acknowledgement,
                        "acknowledgement cannot be null"
                );

        UUID playerId =
                nonNullPlayer.getUniqueId();

        PlayerState state = states.get(playerId);

        if (state == null) {
            return Cancellation.inactive();
        }

        PendingAcquisition acquisition =
                state.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        ActiveRequest activeRequest =
                activeRequests.get(
                        nonNullAcquisitionId
                );

        if (acquisition == null
                || acquisition.player() != nonNullPlayer
                || acquisition.attemptId() != expectedAttemptId
                || !acquisition.acquisitionResultClaimed()
                || activeRequest == null
                || activeRequest.player() != nonNullPlayer
                || activeRequest.attemptId()
                != expectedAttemptId
                || !activeRequest.session()
                .equals(nonNullExpectedSession)) {
            return Cancellation.inactive();
        }

        return cancel(
                nonNullPlayer,
                nonNullAcquisitionId,
                nonNullAcknowledgement
        );
    }

    public synchronized Cancellation claimPendingReleaseTimeout(
            Player player,
            UUID acquisitionId,
            long expectedAttemptId,
            AuthenticatedPlayerSession expectedSession,
            CompletionStage<Boolean> expectedCompletion,
            TerminalAcknowledgement acknowledgement
    ) {
        Player nonNullPlayer = requirePlayer(player);

        UUID nonNullAcquisitionId =
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                );

        if (expectedAttemptId <= 0) {
            throw new IllegalArgumentException(
                    "expectedAttemptId must be greater than zero"
            );
        }

        AuthenticatedPlayerSession nonNullExpectedSession =
                Objects.requireNonNull(
                        expectedSession,
                        "expectedSession cannot be null"
                );

        CompletionStage<Boolean> nonNullExpectedCompletion =
                Objects.requireNonNull(
                        expectedCompletion,
                        "expectedCompletion cannot be null"
                );

        TerminalAcknowledgement nonNullAcknowledgement =
                Objects.requireNonNull(
                        acknowledgement,
                        "acknowledgement cannot be null"
                );

        UUID playerId =
                nonNullPlayer.getUniqueId();

        PlayerState state = states.get(playerId);

        if (state == null) {
            return Cancellation.inactive();
        }

        PendingAcquisition acquisition =
                state.pendingAcquisitions()
                        .get(nonNullAcquisitionId);

        ActiveRequest activeRequest =
                activeRequests.get(
                        nonNullAcquisitionId
                );

        PendingRelease pendingRelease =
                acquisition == null
                        ? null
                        : acquisition.pendingRelease();

        if (acquisition == null
                || acquisition.player() != nonNullPlayer
                || acquisition.attemptId() != expectedAttemptId
                || !acquisition.acquisitionResultClaimed()
                || pendingRelease == null
                || !pendingRelease.lease()
                .session()
                .playerId()
                .equals(playerId)
                || pendingRelease.completion()
                != nonNullExpectedCompletion
                || activeRequest == null
                || activeRequest.player() != nonNullPlayer
                || activeRequest.attemptId()
                != expectedAttemptId
                || !activeRequest.session()
                .equals(nonNullExpectedSession)) {
            return Cancellation.inactive();
        }

        PlayerSessionLease releaseLease =
                pendingRelease.lease();

        TrackedRelease trackedRelease =
                pendingReleases.get(releaseLease);

        boolean releaseAwaitable =
                trackedRelease != null
                        && trackedRelease.completion()
                        == nonNullExpectedCompletion
                        && trackedRelease.lease()
                        .equals(releaseLease);

        boolean releaseQuarantined =
                isExactQuarantine(
                        playerId,
                        releaseLease,
                        nonNullExpectedCompletion
                );

        if (!releaseAwaitable && !releaseQuarantined) {
            return Cancellation.inactive();
        }

        Cancellation cancellation =
                cancel(
                nonNullPlayer,
                nonNullAcquisitionId,
                nonNullAcknowledgement
        );

        if (releaseAwaitable) {
            quarantineRelease(
                    releaseLease,
                    nonNullExpectedCompletion
            );
        }

        removeExactQuarantineFailClosed(
                releaseLease,
                nonNullExpectedCompletion,
                nonNullAcknowledgement
        );

        nonNullExpectedCompletion
                .toCompletableFuture()
                .complete(false);

        return cancellation;
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
            if (isQuarantined(
                    playerId,
                    existingRelease.completion()
            )) {
                return Optional.empty();
            }

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

        for (Map.Entry<PlayerSessionLease, TrackedRelease> entry
                : pendingReleases.entrySet()) {
            PlayerSessionLease candidate =
                    entry.getKey();
            TrackedRelease trackedRelease =
                    entry.getValue();

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
                    <= Math.max(
                    acquisition.minimumFencingTokenExclusive(),
                    fencingFloorFor(playerId)
            )) {
                continue;
            }

            if (pendingLease == null
                    || candidate.fencingToken()
                    > pendingLease.fencingToken()) {
                pendingLease = candidate;
                completion = trackedRelease.completion();
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

        ActiveRequest activeRequest =
                activeRequests.get(
                        nonNullAcquisitionId
                );

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
                != nonNullExpected
                || activeRequest == null
                || activeRequest.player() != nonNullPlayer
                || activeRequest.attemptId()
                != expectedAttemptId) {
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

        activeRequests.put(
                nonNullAcquisitionId,
                new ActiveRequest(
                        activeRequest.player(),
                        activeRequest.session(),
                        retryAttemptId
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
            TrackedRelease trackedRelease =
                    pendingReleases.remove(
                            nonNullLease
                    );

            completion =
                    trackedRelease == null
                            ? null
                            : trackedRelease.completion();

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

    public void completeRelease(
            PlayerSessionLease lease,
            CompletionStage<Boolean> expectedCompletion,
            boolean released
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        CompletionStage<Boolean> nonNullExpected =
                Objects.requireNonNull(
                        expectedCompletion,
                        "expectedCompletion cannot be null"
                );

        CompletableFuture<Boolean> completion;

        synchronized (this) {
            TrackedRelease trackedRelease =
                    pendingReleases.get(nonNullLease);

            if (trackedRelease != null) {
                if (trackedRelease.externalCompletion()
                        != nonNullExpected) {
                    return;
                }

                pendingReleases.remove(nonNullLease);
                completion = trackedRelease.completion();

                attachReleaseCompletionToPendingAcquisitions(
                        nonNullLease,
                        completion
                );
            } else {
                completion =
                        removeReleaseQuarantine(
                                nonNullLease,
                                nonNullExpected
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
            TrackedRelease trackedRelease =
                    pendingReleases.remove(
                            nonNullLease
                    );

            completion =
                    trackedRelease == null
                            ? null
                            : trackedRelease.completion();

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

    public boolean failReleaseBeforeExternalAttachment(
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
            TrackedRelease trackedRelease =
                    pendingReleases.get(nonNullLease);

            if (trackedRelease != null) {
                if (trackedRelease.externalCompletion() != null) {
                    return false;
                }

                pendingReleases.remove(nonNullLease);
                completion = trackedRelease.completion();

                attachReleaseCompletionToPendingAcquisitions(
                        nonNullLease,
                        completion
                );
            } else {
                ReleaseQuarantineKey key =
                        findUnattachedReleaseQuarantineKey(
                                nonNullLease
                        );

                if (key == null) {
                    return false;
                }

                ReleaseQuarantine quarantine =
                        releaseQuarantines.remove(key);
                completion = quarantine.completion();
            }
        }

        completion.completeExceptionally(nonNullFailure);
        return true;
    }

    public void failRelease(
            PlayerSessionLease lease,
            CompletionStage<Boolean> expectedCompletion,
            Throwable failure
    ) {
        PlayerSessionLease nonNullLease =
                Objects.requireNonNull(
                        lease,
                        "lease cannot be null"
                );

        CompletionStage<Boolean> nonNullExpected =
                Objects.requireNonNull(
                        expectedCompletion,
                        "expectedCompletion cannot be null"
                );

        Throwable nonNullFailure =
                Objects.requireNonNull(
                        failure,
                        "failure cannot be null"
                );

        CompletableFuture<Boolean> completion;

        synchronized (this) {
            TrackedRelease trackedRelease =
                    pendingReleases.get(nonNullLease);

            if (trackedRelease != null) {
                if (trackedRelease.externalCompletion()
                        != nonNullExpected) {
                    return;
                }

                pendingReleases.remove(nonNullLease);
                completion = trackedRelease.completion();

                attachReleaseCompletionToPendingAcquisitions(
                        nonNullLease,
                        completion
                );
            } else {
                completion =
                        removeReleaseQuarantine(
                                nonNullLease,
                                nonNullExpected
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
        releaseQuarantines.clear();
        releaseFencingFloors.clear();
        unknownFencingFloorAdmissionsClosed = false;
        activeRequests.clear();
        terminalRequests.clear();
    }

    private List<QuarantinedReleaseTimeout> quarantineRelease(
            PlayerSessionLease lease,
            CompletionStage<Boolean> completion
    ) {
        TrackedRelease trackedRelease =
                pendingReleases.get(lease);

        if (trackedRelease == null
                || trackedRelease.completion() != completion) {
            return List.of();
        }

        pendingReleases.remove(lease);

        return quarantineRelease(
                lease,
                trackedRelease.completion(),
                trackedRelease.externalCompletion()
        );
    }

    private List<QuarantinedReleaseTimeout> quarantineRelease(
            PlayerSessionLease lease,
            CompletableFuture<Boolean> completion,
            CompletionStage<Boolean> externalCompletion
    ) {
        UUID playerId =
                lease.session().playerId();

        FencingFloorRegistration floorRegistration =
                recordFencingFloor(
                playerId,
                lease.fencingToken()
        );

        if (floorRegistration
                == FencingFloorRegistration.CAPACITY_EXHAUSTED) {
            terminalizeExactWaiters(
                    playerId,
                    lease,
                    completion,
                    failClosedAcknowledgement()
            );
            completion.complete(false);
            return List.of();
        }

        List<QuarantinedReleaseTimeout> evicted =
                evictQuarantinesForCapacity();

        ReleaseQuarantineKey key =
                new ReleaseQuarantineKey(
                        lease,
                        completion
                );

        releaseQuarantines.put(
                key,
                new ReleaseQuarantine(
                        key,
                        externalCompletion,
                        expiresAtReleaseQuarantine()
                )
        );

        return evicted;
    }

    private boolean isQuarantined(
            UUID playerId,
            CompletionStage<Boolean> completion
    ) {
        return releaseQuarantines
                .keySet()
                .stream()
                .anyMatch(key ->
                        key.playerId().equals(playerId)
                                && key.completion()
                                == completion
                );
    }

    private boolean isExactQuarantine(
            UUID playerId,
            PlayerSessionLease lease,
            CompletionStage<Boolean> completion
    ) {
        return findExactReleaseQuarantineKey(
                playerId,
                lease,
                completion
        ) != null;
    }

    private CompletableFuture<Boolean> removeReleaseQuarantine(
            PlayerSessionLease lease,
            CompletionStage<Boolean> completion
    ) {
        ReleaseQuarantineKey key =
                findReleaseQuarantineKey(
                        lease,
                        completion
                );

        if (key == null) {
            return null;
        }

        ReleaseQuarantine quarantine =
                releaseQuarantines.remove(key);

        return quarantine == null
                ? null
                : quarantine.completion();
    }

    synchronized int expireReleaseQuarantines() {
        long now = monotonicTime.getAsLong();
        List<ReleaseQuarantineKey> expired =
                releaseQuarantines
                        .entrySet()
                        .stream()
                        .filter(entry ->
                                entry.getValue()
                                        .expiresAt()
                                        <= now
                        )
                        .map(Map.Entry::getKey)
                        .toList();

        for (ReleaseQuarantineKey key : expired) {
            removeExactQuarantineFailClosed(
                    key.lease(),
                    key.completion(),
                    failClosedAcknowledgement()
            );
        }

        return expired.size();
    }

    synchronized int exactQuarantineCount() {
        return releaseQuarantines.size();
    }

    private ReleaseQuarantineKey findUnattachedReleaseQuarantineKey(
            PlayerSessionLease lease
    ) {
        for (Map.Entry<ReleaseQuarantineKey, ReleaseQuarantine>
                entry : releaseQuarantines.entrySet()) {
            ReleaseQuarantineKey key =
                    entry.getKey();
            ReleaseQuarantine quarantine =
                    entry.getValue();

            if (key.lease().equals(lease)
                    && quarantine.externalCompletion() == null) {
                return key;
            }
        }

        return null;
    }

    private ReleaseQuarantineKey findReleaseQuarantineKey(
            PlayerSessionLease lease,
            CompletionStage<Boolean> externalCompletion
    ) {
        for (Map.Entry<ReleaseQuarantineKey, ReleaseQuarantine>
                entry : releaseQuarantines.entrySet()) {
            ReleaseQuarantineKey key =
                    entry.getKey();
            ReleaseQuarantine quarantine =
                    entry.getValue();

            if (key.lease().equals(lease)
                    && quarantine.externalCompletion()
                    == externalCompletion) {
                return key;
            }
        }

        return null;
    }

    private ReleaseQuarantineKey findExactReleaseQuarantineKey(
            UUID playerId,
            PlayerSessionLease lease,
            CompletionStage<Boolean> completion
    ) {
        for (ReleaseQuarantineKey key
                : releaseQuarantines.keySet()) {
            if (key.playerId().equals(playerId)
                    && key.lease().equals(lease)
                    && key.completion() == completion) {
                return key;
            }
        }

        return null;
    }

    private void removeExactQuarantineFailClosed(
            PlayerSessionLease lease,
            CompletionStage<Boolean> completion,
            TerminalAcknowledgement acknowledgement
    ) {
        ReleaseQuarantineKey key =
                findExactReleaseQuarantineKey(
                        lease.session().playerId(),
                        lease,
                        completion
                );

        if (key == null) {
            return;
        }

        ReleaseQuarantine quarantine =
                releaseQuarantines.remove(key);

        if (quarantine == null) {
            return;
        }

        terminalizeExactQuarantineWaiters(
                quarantine,
                acknowledgement
        );

        quarantine.completion().complete(false);
    }

    private void terminalizeExactQuarantineWaiters(
            ReleaseQuarantine quarantine,
            TerminalAcknowledgement acknowledgement
    ) {
        terminalizeExactWaiters(
                quarantine.playerId(),
                quarantine.lease(),
                quarantine.completion(),
                acknowledgement
        );
    }

    private void terminalizeExactWaiters(
            UUID playerId,
            PlayerSessionLease lease,
            CompletionStage<Boolean> completion,
            TerminalAcknowledgement acknowledgement
    ) {
        UUID nonNullPlayerId =
                Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                );
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
        TerminalAcknowledgement nonNullAcknowledgement =
                Objects.requireNonNull(
                        acknowledgement,
                        "acknowledgement cannot be null"
                );

        PlayerState state =
                states.get(nonNullPlayerId);

        if (state == null
                || state.pendingAcquisitions().isEmpty()) {
            return;
        }

        Map<UUID, PendingAcquisition> remaining =
                new HashMap<>(
                        state.pendingAcquisitions()
                );

        for (Map.Entry<UUID, PendingAcquisition> entry
                : state.pendingAcquisitions().entrySet()) {
            PendingRelease pendingRelease =
                    entry.getValue()
                            .pendingRelease();

            if (pendingRelease == null
                    || !pendingRelease.lease()
                    .equals(nonNullLease)
                    || pendingRelease.completion()
                    != nonNullCompletion) {
                continue;
            }

            remaining.remove(entry.getKey());
            completeActiveRequest(
                    entry.getKey(),
                    nonNullAcknowledgement
            );
        }

        updateState(
                nonNullPlayerId,
                new PlayerState(
                        state.boundLease(),
                        remaining
                )
        );
    }

    private List<QuarantinedReleaseTimeout>
    evictQuarantinesForCapacity() {
        List<QuarantinedReleaseTimeout> evicted =
                new ArrayList<>();

        while (releaseQuarantines.size()
                >= releaseQuarantineCapacity) {
            ReleaseQuarantineKey eldest =
                    releaseQuarantines
                            .keySet()
                            .iterator()
                            .next();

            ReleaseQuarantine quarantine =
                    releaseQuarantines.get(eldest);

            if (quarantine != null) {
                evicted.add(
                        new QuarantinedReleaseTimeout(
                                quarantine.lease(),
                                quarantine.externalCompletion()
                        )
                );
            }

            removeExactQuarantineFailClosed(
                    eldest.lease(),
                    eldest.completion(),
                    failClosedAcknowledgement()
            );
        }

        return List.copyOf(evicted);
    }

    private FencingFloorRegistration recordFencingFloor(
            UUID playerId,
            long fencingToken
    ) {
        FencingFloor existing =
                releaseFencingFloors.get(playerId);

        if (existing != null) {
            releaseFencingFloors.put(
                    playerId,
                    new FencingFloor(
                            Math.max(
                                    existing.fencingFloor(),
                                    fencingToken
                            )
                    )
            );
            return FencingFloorRegistration.REGISTERED;
        }

        if (releaseFencingFloors.size()
                >= fencingFloorCapacity) {
            unknownFencingFloorAdmissionsClosed = true;
            return FencingFloorRegistration.CAPACITY_EXHAUSTED;
        }

        releaseFencingFloors.put(
                playerId,
                new FencingFloor(fencingToken)
        );

        return FencingFloorRegistration.REGISTERED;
    }

    private boolean hasExactQuarantine(UUID playerId) {
        return releaseQuarantines
                .keySet()
                .stream()
                .anyMatch(key ->
                        key.playerId().equals(playerId)
                );
    }

    private long expiresAtReleaseQuarantine() {
        long now = monotonicTime.getAsLong();

        try {
            return Math.addExact(
                    now,
                    releaseQuarantineTtl
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private TerminalAcknowledgement failClosedAcknowledgement() {
        return new TerminalAcknowledgement(
                false,
                "Player session coordination unavailable"
        );
    }

    private long fencingFloorFor(UUID playerId) {
        FencingFloor floor =
                releaseFencingFloors.get(playerId);

        return floor == null
                ? 0L
                : floor.fencingFloor();
    }

    private void clearReleaseQuarantineIfSuperseded(
            UUID playerId,
            PlayerSessionLease lease
    ) {
        // Exact release quarantines are operation-owned. A newer
        // binding may pass the fencing floor, but it must not clear
        // unrelated release callbacks or waiter timeouts.
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
    private void completeSuccessfulBinding(
            UUID acquisitionId,
            Player player,
            PlayerSessionLease lease,
            TerminalAcknowledgement acknowledgement
    ) {
        completeActiveRequest(
                acquisitionId,
                new SuccessfulReplayBinding(
                        player,
                        lease
                ),
                acknowledgement
        );
    }

    private void discardActiveRequestWithoutReplay(
            UUID acquisitionId,
            Player expectedPlayer,
            long expectedAttemptId,
            AuthenticatedPlayerSession expectedSession
    ) {
        ActiveRequest activeRequest =
                activeRequests.get(acquisitionId);

        if (activeRequest == null
                || activeRequest.player() != expectedPlayer
                || activeRequest.attemptId()
                != expectedAttemptId
                || !activeRequest.session()
                .equals(expectedSession)) {
            return;
        }

        activeRequests.remove(
                acquisitionId,
                activeRequest
        );
    }

    private void completeActiveRequest(
            UUID acquisitionId,
            TerminalAcknowledgement acknowledgement
    ) {
        completeActiveRequest(
                acquisitionId,
                null,
                acknowledgement
        );
    }

    private void completeActiveRequest(
            UUID acquisitionId,
            SuccessfulReplayBinding successfulReplayBinding,
            TerminalAcknowledgement acknowledgement
    ) {
        ActiveRequest active =
                activeRequests.remove(acquisitionId);

        if (active == null) {
            return;
        }

        TerminalAcknowledgement nonNullAcknowledgement =
                Objects.requireNonNull(
                        acknowledgement,
                        "acknowledgement cannot be null"
                );

        purgeExpiredTerminalRequests();

        rememberTerminal(
                acquisitionId,
                new TerminalRequest(
                        active.session(),
                        active.attemptId(),
                        nonNullAcknowledgement,
                        Optional.ofNullable(
                                successfulReplayBinding
                        ),
                        terminalExpirationMillis()
                )
        );
    }

    private boolean hasLiveSuccessfulReplayBinding(
            Player player,
            TerminalRequest terminal
    ) {
        SuccessfulReplayBinding replayBinding =
                terminal
                        .successfulReplayBinding()
                        .orElse(null);

        if (replayBinding == null
                || replayBinding.player() != player) {
            return false;
        }

        PlayerSessionLease lease =
                replayBinding.lease();

        if (!lease.session()
                .equals(terminal.session())) {
            return false;
        }

        PlayerState state =
                states.get(player.getUniqueId());

        if (state == null
                || state.boundLease().isEmpty()) {
            return false;
        }

        BoundLease bound =
                state.boundLease().orElseThrow();

        return bound.player() == player
                && !bound.disconnected()
                && bound.lease().equals(lease);
    }

    private void rememberTerminal(
            UUID acquisitionId,
            TerminalRequest request
    ) {
        terminalRequests.put(
                Objects.requireNonNull(
                        acquisitionId,
                        "acquisitionId cannot be null"
                ),
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                )
        );

        if (terminalRequests.size()
                > requestCapacity) {
            throw new IllegalStateException(
                    "terminal request capacity invariant violated"
            );
        }
    }

    private void purgeExpiredTerminalRequests() {
        long now = monotonicTime.getAsLong();

        terminalRequests
                .entrySet()
                .removeIf(entry ->
                        entry.getValue()
                                .expiresAtMillis()
                                <= now
                );
    }

    private int trackedRequestCount() {
        return Math.addExact(
                activeRequests.size(),
                terminalRequests.size()
        );
    }

    private long terminalExpirationMillis() {
        long now = monotonicTime.getAsLong();

        try {
            return Math.addExact(
                    now,
                    terminalReplayWindow
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
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

    public enum BeginDecision {
        PROCEED,
        PENDING_REPLAY,
        COMPLETED_REPLAY,
        CONFLICT,
        CAPACITY_EXHAUSTED
    }

    private enum FencingFloorRegistration {
        REGISTERED,
        CAPACITY_EXHAUSTED
    }

    public record BeginResult(
            BeginDecision decision,
            long attemptId,
            Optional<PlayerSessionLease> leaseToRelease,
            Optional<TerminalAcknowledgement> acknowledgement
    ) {

        public BeginResult {
            decision = Objects.requireNonNull(
                    decision,
                    "decision cannot be null"
            );

            leaseToRelease = Objects.requireNonNull(
                    leaseToRelease,
                    "leaseToRelease cannot be null"
            );

            acknowledgement = Objects.requireNonNull(
                    acknowledgement,
                    "acknowledgement cannot be null"
            );

            if (decision == BeginDecision.PROCEED
                    && attemptId <= 0) {
                throw new IllegalArgumentException(
                        "proceed attempt id must be greater than zero"
                );
            }

            if (decision != BeginDecision.PROCEED
                    && attemptId != 0) {
                throw new IllegalArgumentException(
                        "non-proceed attempt id must be zero"
                );
            }

            if (decision != BeginDecision.COMPLETED_REPLAY
                    && acknowledgement.isPresent()) {
                throw new IllegalArgumentException(
                        "only completed replay may expose an acknowledgement"
                );
            }
        }

        private static BeginResult proceed(
                long attemptId
        ) {
            return new BeginResult(
                    BeginDecision.PROCEED,
                    attemptId,
                    Optional.empty(),
                    Optional.empty()
            );
        }

        private static BeginResult pendingReplay() {
            return new BeginResult(
                    BeginDecision.PENDING_REPLAY,
                    0L,
                    Optional.empty(),
                    Optional.empty()
            );
        }

        private static BeginResult completedReplay(
                TerminalAcknowledgement acknowledgement
        ) {
            return new BeginResult(
                    BeginDecision.COMPLETED_REPLAY,
                    0L,
                    Optional.empty(),
                    Optional.of(
                            Objects.requireNonNull(
                                    acknowledgement,
                                    "acknowledgement cannot be null"
                            )
                    )
            );
        }

        private static BeginResult conflict(
                Optional<PlayerSessionLease> leaseToRelease
        ) {
            return new BeginResult(
                    BeginDecision.CONFLICT,
                    0L,
                    leaseToRelease,
                    Optional.empty()
            );
        }

        private static BeginResult capacityExhausted() {
            return new BeginResult(
                    BeginDecision.CAPACITY_EXHAUSTED,
                    0L,
                    Optional.empty(),
                    Optional.empty()
            );
        }
    }

    public record TerminalAcknowledgement(
            boolean successful,
            String message
    ) {

        public TerminalAcknowledgement {
            message = Objects.requireNonNull(
                    message,
                    "message cannot be null"
            );
        }
    }

    public record ReleaseTimeoutClaim(
            boolean claimed,
            List<QuarantinedReleaseTimeout> evictedQuarantines
    ) {

        public ReleaseTimeoutClaim {
            evictedQuarantines =
                    List.copyOf(
                            Objects.requireNonNull(
                                    evictedQuarantines,
                                    "evictedQuarantines cannot be null"
                            )
                    );
        }

        private static ReleaseTimeoutClaim claimed(
                List<QuarantinedReleaseTimeout> evictedQuarantines
        ) {
            return new ReleaseTimeoutClaim(
                    true,
                    evictedQuarantines
            );
        }

        private static ReleaseTimeoutClaim notClaimed() {
            return new ReleaseTimeoutClaim(
                    false,
                    List.of()
            );
        }
    }

    public record QuarantinedReleaseTimeout(
            PlayerSessionLease lease,
            CompletionStage<Boolean> externalCompletion
    ) {

        public QuarantinedReleaseTimeout {
            lease = Objects.requireNonNull(
                    lease,
                    "lease cannot be null"
            );

            externalCompletion =
                    Objects.requireNonNull(
                            externalCompletion,
                            "externalCompletion cannot be null"
                    );
        }
    }

    private record ActiveRequest(
            Player player,
            AuthenticatedPlayerSession session,
            long attemptId
    ) {

        private ActiveRequest {
            Objects.requireNonNull(
                    player,
                    "player cannot be null"
            );

            Objects.requireNonNull(
                    session,
                    "session cannot be null"
            );

            if (attemptId <= 0) {
                throw new IllegalArgumentException(
                        "attemptId must be greater than zero"
                );
            }
        }
    }

    private record TerminalRequest(
            AuthenticatedPlayerSession session,
            long attemptId,
            TerminalAcknowledgement acknowledgement,
            Optional<SuccessfulReplayBinding>
                    successfulReplayBinding,
            long expiresAtMillis
    ) {

        private TerminalRequest {
            Objects.requireNonNull(
                    session,
                    "session cannot be null"
            );

            if (attemptId <= 0) {
                throw new IllegalArgumentException(
                        "attemptId must be greater than zero"
                );
            }

            Objects.requireNonNull(
                    acknowledgement,
                    "acknowledgement cannot be null"
            );

            successfulReplayBinding =
                    Objects.requireNonNull(
                            successfulReplayBinding,
                            "successfulReplayBinding cannot be null"
                    );

            if (!acknowledgement.successful()
                    && successfulReplayBinding.isPresent()) {
                throw new IllegalArgumentException(
                        "failed terminal requests cannot carry "
                                + "successful replay binding"
                );
            }

            if (acknowledgement.successful()
                    && successfulReplayBinding.isEmpty()) {
                throw new IllegalArgumentException(
                        "successful terminal requests require "
                                + "successful replay binding"
                );
            }
        }

    }

    private record SuccessfulReplayBinding(
            Player player,
            PlayerSessionLease lease
    ) {

        private SuccessfulReplayBinding {
            Objects.requireNonNull(
                    player,
                    "player cannot be null"
            );

            Objects.requireNonNull(
                    lease,
                    "lease cannot be null"
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

    private record TrackedRelease(
            PlayerSessionLease lease,
            CompletableFuture<Boolean> completion,
            CompletionStage<Boolean> externalCompletion
    ) {

        private TrackedRelease {
            Objects.requireNonNull(
                    lease,
                    "lease cannot be null"
            );

            Objects.requireNonNull(
                    completion,
                    "completion cannot be null"
            );

        }

        private static TrackedRelease awaitable(
                PlayerSessionLease lease,
                CompletableFuture<Boolean> completion,
                CompletionStage<Boolean> externalCompletion
        ) {
            return new TrackedRelease(
                    lease,
                    completion,
                    externalCompletion
            );
        }

        private TrackedRelease withExternalCompletion(
                CompletionStage<Boolean> newExternalCompletion
        ) {
            return new TrackedRelease(
                    lease,
                    completion,
                    Objects.requireNonNull(
                            newExternalCompletion,
                            "newExternalCompletion cannot be null"
                    )
            );
        }
    }

    private record ReleaseQuarantineKey(
            PlayerSessionLease lease,
            CompletableFuture<Boolean> completion
    ) {

        private ReleaseQuarantineKey {
            Objects.requireNonNull(
                    lease,
                    "lease cannot be null"
            );

            completion =
                    Objects.requireNonNull(
                            completion,
                            "completion cannot be null"
                    );
        }

        private UUID playerId() {
            return lease.session().playerId();
        }
    }

    private record FencingFloor(
            long fencingFloor
    ) {

        private FencingFloor {
            if (fencingFloor <= 0) {
                throw new IllegalArgumentException(
                        "fencingFloor must be greater than zero"
                );
            }
        }
    }

    private record ReleaseQuarantine(
            ReleaseQuarantineKey key,
            CompletionStage<Boolean> externalCompletion,
            long expiresAt
    ) {

        private ReleaseQuarantine {
            Objects.requireNonNull(
                    key,
                    "key cannot be null"
            );

            if (expiresAt <= 0) {
                throw new IllegalArgumentException(
                        "expiresAt must be greater than zero"
                );
            }
        }

        private PlayerSessionLease lease() {
            return key.lease();
        }

        private UUID playerId() {
            return key.playerId();
        }

        private CompletableFuture<Boolean> completion() {
            return key.completion();
        }

        private ReleaseQuarantine withExternalCompletion(
                CompletionStage<Boolean> newExternalCompletion
        ) {
            return new ReleaseQuarantine(
                    key,
                    Objects.requireNonNull(
                            newExternalCompletion,
                            "newExternalCompletion cannot be null"
                    ),
                    expiresAt
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

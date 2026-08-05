package com.theosfera.proxy.transfer;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.PlayerSessionLease;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BackendCapacityHandoffRegistry {

    private final Map<UUID, BackendCapacityReserveRequest> byPlayerId =
            new HashMap<>();
    private final Map<UUID, BackendCapacityReserveRequest> byRequestId =
            new HashMap<>();

    public synchronized BackendCapacityHandoffRegistrationResult register(
            BackendCapacityReserveRequest request
    ) {
        BackendCapacityReserveRequest nonNullRequest =
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                );

        UUID playerId = nonNullRequest
                .reservation()
                .playerId();
        UUID requestId = nonNullRequest
                .reservation()
                .requestId();

        BackendCapacityReserveRequest existingByRequest =
                byRequestId.get(requestId);

        if (existingByRequest != null) {
            if (existingByRequest.equals(nonNullRequest)) {
                BackendCapacityReserveRequest existingByPlayer =
                        byPlayerId.get(playerId);
                if (!nonNullRequest.equals(existingByPlayer)) {
                    throw new IllegalStateException(
                            "capacity handoff registry indexes are inconsistent"
                    );
                }
                return BackendCapacityHandoffRegistrationResult
                        .ALREADY_REGISTERED;
            }
            return BackendCapacityHandoffRegistrationResult
                    .REQUEST_ID_CONFLICT;
        }

        BackendCapacityReserveRequest existingByPlayer =
                byPlayerId.get(playerId);

        if (existingByPlayer != null) {
            return BackendCapacityHandoffRegistrationResult.PLAYER_BUSY;
        }

        byPlayerId.put(playerId, nonNullRequest);
        byRequestId.put(requestId, nonNullRequest);
        return BackendCapacityHandoffRegistrationResult.REGISTERED;
    }

    public synchronized Optional<BackendCapacityReserveRequest> findByPlayer(
            UUID playerId
    ) {
        return Optional.ofNullable(
                byPlayerId.get(
                        Objects.requireNonNull(
                                playerId,
                                "playerId cannot be null"
                        )
                )
        );
    }

    public synchronized Optional<BackendCapacityReserveRequest> removeIfMatches(
            BackendCapacityReserveRequest expected
    ) {
        BackendCapacityReserveRequest nonNullExpected =
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                );

        UUID playerId = nonNullExpected.reservation().playerId();
        UUID requestId = nonNullExpected.reservation().requestId();

        if (!nonNullExpected.equals(byPlayerId.get(playerId))
                || !nonNullExpected.equals(byRequestId.get(requestId))) {
            return Optional.empty();
        }

        byPlayerId.remove(playerId);
        byRequestId.remove(requestId);
        return Optional.of(nonNullExpected);
    }

    public synchronized Optional<BackendCapacityReserveRequest>
    removeForSessionLease(PlayerSessionLease sessionLease) {
        PlayerSessionLease nonNullLease = Objects.requireNonNull(
                sessionLease,
                "sessionLease cannot be null"
        );

        UUID playerId = nonNullLease.session().playerId();
        BackendCapacityReserveRequest existing = byPlayerId.get(playerId);

        if (existing == null
                || !existing.sessionLease().equals(nonNullLease)) {
            return Optional.empty();
        }

        return removeIfMatches(existing);
    }

    public synchronized int size() {
        return byPlayerId.size();
    }

    public synchronized void clear() {
        byPlayerId.clear();
        byRequestId.clear();
    }
}

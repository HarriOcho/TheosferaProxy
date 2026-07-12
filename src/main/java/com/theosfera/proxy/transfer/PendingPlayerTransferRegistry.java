package com.theosfera.proxy.transfer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingPlayerTransferRegistry {

    private final Map<UUID, PendingPlayerTransfer> transfersByPlayer =
            new ConcurrentHashMap<>();

    private final Map<UUID, PendingPlayerTransfer> transfersByRequest =
            new ConcurrentHashMap<>();

    public synchronized PlayerTransferRegistrationResult register(
            PendingPlayerTransfer transfer
    ) {
        PendingPlayerTransfer nonNullTransfer =
                Objects.requireNonNull(
                        transfer,
                        "transfer cannot be null"
                );

        PendingPlayerTransfer requestTransfer =
                transfersByRequest.get(
                        nonNullTransfer.requestId()
                );

        if (requestTransfer != null) {
            if (requestTransfer.equals(nonNullTransfer)) {
                return PlayerTransferRegistrationResult
                        .ALREADY_REGISTERED;
            }

            return PlayerTransferRegistrationResult
                    .REQUEST_ID_CONFLICT;
        }

        PendingPlayerTransfer playerTransfer =
                transfersByPlayer.get(
                        nonNullTransfer.playerId()
                );

        if (playerTransfer != null) {
            if (playerTransfer.equals(nonNullTransfer)) {
                return PlayerTransferRegistrationResult
                        .ALREADY_REGISTERED;
            }

            return PlayerTransferRegistrationResult.PLAYER_BUSY;
        }

        transfersByPlayer.put(
                nonNullTransfer.playerId(),
                nonNullTransfer
        );

        transfersByRequest.put(
                nonNullTransfer.requestId(),
                nonNullTransfer
        );

        return PlayerTransferRegistrationResult.REGISTERED;
    }

    public Optional<PendingPlayerTransfer> findByPlayer(
            UUID playerId
    ) {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        return Optional.ofNullable(
                transfersByPlayer.get(playerId)
        );
    }

    public Optional<PendingPlayerTransfer> findByRequest(
            UUID requestId
    ) {
        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );

        return Optional.ofNullable(
                transfersByRequest.get(requestId)
        );
    }

    public synchronized Optional<PendingPlayerTransfer> remove(
            UUID requestId
    ) {
        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );

        PendingPlayerTransfer removed =
                transfersByRequest.remove(requestId);

        if (removed == null) {
            return Optional.empty();
        }

        transfersByPlayer.remove(
                removed.playerId(),
                removed
        );

        return Optional.of(removed);
    }

    public synchronized Optional<PendingPlayerTransfer> removeByPlayer(
            UUID playerId
    ) {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        PendingPlayerTransfer removed =
                transfersByPlayer.remove(playerId);

        if (removed == null) {
            return Optional.empty();
        }

        transfersByRequest.remove(
                removed.requestId(),
                removed
        );

        return Optional.of(removed);
    }

    public Map<UUID, PendingPlayerTransfer> snapshotByPlayer() {
        return Map.copyOf(transfersByPlayer);
    }

    public synchronized void clear() {
        transfersByRequest.clear();
        transfersByPlayer.clear();
    }
}

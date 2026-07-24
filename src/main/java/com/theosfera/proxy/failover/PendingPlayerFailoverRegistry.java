package com.theosfera.proxy.failover;

import com.theosfera.proxy.transfer.BackendBootstrapReservation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingPlayerFailoverRegistry {

    private final Set<UUID> pendingPlayers =
            ConcurrentHashMap.newKeySet();

    private final Map<UUID, BackendBootstrapReservation>
            bootstrapReservationsByPlayer =
            new ConcurrentHashMap<>();

    public synchronized boolean reserve(UUID playerId) {
        return pendingPlayers.add(
                requirePlayerId(playerId)
        );
    }

    public synchronized boolean reserve(
            UUID playerId,
            BackendBootstrapReservation bootstrapReservation
    ) {
        UUID nonNullPlayerId =
                requirePlayerId(playerId);

        BackendBootstrapReservation nonNullReservation =
                Objects.requireNonNull(
                        bootstrapReservation,
                        "bootstrapReservation cannot be null"
                );

        if (!nonNullReservation
                .playerId()
                .equals(nonNullPlayerId)) {
            throw new IllegalArgumentException(
                    "bootstrapReservation must belong to playerId"
            );
        }

        if (!pendingPlayers.add(nonNullPlayerId)) {
            return false;
        }

        bootstrapReservationsByPlayer.put(
                nonNullPlayerId,
                nonNullReservation
        );

        return true;
    }

    public synchronized boolean clear(UUID playerId) {
        return pendingPlayers.remove(
                requirePlayerId(playerId)
        );
    }

    public synchronized Optional<BackendBootstrapReservation>
    clearForDisconnect(UUID playerId) {
        UUID nonNullPlayerId =
                requirePlayerId(playerId);

        pendingPlayers.remove(nonNullPlayerId);

        return Optional.ofNullable(
                bootstrapReservationsByPlayer.remove(
                        nonNullPlayerId
                )
        );
    }

    public boolean isReserved(UUID playerId) {
        return pendingPlayers.contains(
                requirePlayerId(playerId)
        );
    }

    public int size() {
        return pendingPlayers.size();
    }

    public synchronized void clear() {
        pendingPlayers.clear();
        bootstrapReservationsByPlayer.clear();
    }

    private UUID requirePlayerId(UUID playerId) {
        return Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );
    }
}

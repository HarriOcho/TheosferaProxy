package com.theosfera.proxy.failover;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingPlayerFailoverRegistry {

    private final Set<UUID> pendingPlayers =
            ConcurrentHashMap.newKeySet();

    public boolean reserve(UUID playerId) {
        return pendingPlayers.add(
                Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                )
        );
    }

    public boolean clear(UUID playerId) {
        return pendingPlayers.remove(
                Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                )
        );
    }

    public boolean isReserved(UUID playerId) {
        return pendingPlayers.contains(
                Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                )
        );
    }

    public int size() {
        return pendingPlayers.size();
    }

    public void clear() {
        pendingPlayers.clear();
    }
}

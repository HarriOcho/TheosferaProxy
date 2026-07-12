package com.theosfera.proxy.session;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerServerPresenceRegistry {

    private final AuthenticatedPlayerSessionRegistry
            sessionRegistry;

    private final Map<UUID, PlayerServerPresence> presences =
            new ConcurrentHashMap<>();

    public PlayerServerPresenceRegistry(
            AuthenticatedPlayerSessionRegistry sessionRegistry
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
    }

    public PlayerPresenceUpdateResult update(
            PlayerServerPresence presence
    ) {
        PlayerServerPresence nonNullPresence =
                Objects.requireNonNull(
                        presence,
                        "presence cannot be null"
                );

        if (!sessionRegistry.isAuthenticated(
                nonNullPresence.playerId()
        )) {
            return PlayerPresenceUpdateResult
                    .NOT_AUTHENTICATED;
        }

        AtomicReference<PlayerPresenceUpdateResult> result =
                new AtomicReference<>();

        presences.compute(
                nonNullPresence.playerId(),
                (playerId, existing) -> {
                    if (existing == null) {
                        result.set(
                                PlayerPresenceUpdateResult.RECORDED
                        );
                        return nonNullPresence;
                    }

                    if (existing.equals(nonNullPresence)) {
                        result.set(
                                PlayerPresenceUpdateResult
                                        .ALREADY_RECORDED
                        );
                        return existing;
                    }

                    if (nonNullPresence.readyAt()
                            < existing.readyAt()) {
                        result.set(
                                PlayerPresenceUpdateResult.STALE
                        );
                        return existing;
                    }

                    if (nonNullPresence.readyAt()
                            == existing.readyAt()) {
                        result.set(
                                PlayerPresenceUpdateResult.CONFLICT
                        );
                        return existing;
                    }

                    result.set(
                            PlayerPresenceUpdateResult.UPDATED
                    );
                    return nonNullPresence;
                }
        );

        return result.get();
    }

    public Optional<PlayerServerPresence> find(
            UUID playerId
    ) {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        return Optional.ofNullable(
                presences.get(playerId)
        );
    }

    public Optional<PlayerServerPresence> remove(
            UUID playerId
    ) {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        return Optional.ofNullable(
                presences.remove(playerId)
        );
    }

    public boolean removeIfBackend(
            UUID playerId,
            String backendName
    ) {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        String nonNullBackendName =
                Objects.requireNonNull(
                        backendName,
                        "backendName cannot be null"
                );

        AtomicReference<Boolean> removed =
                new AtomicReference<>(false);

        presences.computeIfPresent(
                playerId,
                (ignored, existing) -> {
                    if (!existing.backendName()
                            .equals(nonNullBackendName)) {
                        return existing;
                    }

                    removed.set(true);
                    return null;
                }
        );

        return removed.get();
    }

    public Map<UUID, PlayerServerPresence> snapshot() {
        return Map.copyOf(presences);
    }

    public void clear() {
        presences.clear();
    }
}
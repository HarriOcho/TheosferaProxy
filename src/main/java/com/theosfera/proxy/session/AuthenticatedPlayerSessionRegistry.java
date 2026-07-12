package com.theosfera.proxy.session;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class AuthenticatedPlayerSessionRegistry {

    private final Map<UUID, AuthenticatedPlayerSession> sessions =
            new ConcurrentHashMap<>();

    public PlayerSessionRegistrationResult register(
            AuthenticatedPlayerSession session
    ) {
        AuthenticatedPlayerSession nonNullSession =
                Objects.requireNonNull(
                        session,
                        "session cannot be null"
                );

        AtomicReference<PlayerSessionRegistrationResult> result =
                new AtomicReference<>();

        sessions.compute(
                nonNullSession.playerId(),
                (playerId, existing) -> {
                    if (existing == null) {
                        result.set(
                                PlayerSessionRegistrationResult
                                        .REGISTERED
                        );
                        return nonNullSession;
                    }

                    if (existing.equals(nonNullSession)) {
                        result.set(
                                PlayerSessionRegistrationResult
                                        .ALREADY_REGISTERED
                        );
                        return existing;
                    }

                    result.set(
                            PlayerSessionRegistrationResult.CONFLICT
                    );
                    return existing;
                }
        );

        return result.get();
    }

    public Optional<AuthenticatedPlayerSession> find(
            UUID playerId
    ) {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        return Optional.ofNullable(
                sessions.get(playerId)
        );
    }

    public boolean isAuthenticated(UUID playerId) {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        return sessions.containsKey(playerId);
    }

    public Optional<AuthenticatedPlayerSession> remove(
            UUID playerId
    ) {
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        return Optional.ofNullable(
                sessions.remove(playerId)
        );
    }

    public Map<UUID, AuthenticatedPlayerSession> snapshot() {
        return Map.copyOf(sessions);
    }

    public void clear() {
        sessions.clear();
    }
}
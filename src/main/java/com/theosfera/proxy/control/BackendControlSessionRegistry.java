package com.theosfera.proxy.control;

import com.theosfera.proxy.backend.BackendIdentity;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class BackendControlSessionRegistry {

    private final Map<String, BackendControlSession> sessions =
            new ConcurrentHashMap<>();
    private final AtomicLong generationSequence =
            new AtomicLong();

    public BackendControlSessionRegistration register(
            UUID connectionId,
            BackendIdentity identity
    ) {
        UUID nonNullConnectionId = Objects.requireNonNull(
                connectionId,
                "connectionId cannot be null"
        );
        BackendIdentity nonNullIdentity = Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );

        long generation = generationSequence.incrementAndGet();
        if (generation <= 0) {
            throw new IllegalStateException(
                    "control session generation overflow"
            );
        }

        BackendControlSession next = new BackendControlSession(
                nonNullConnectionId,
                nonNullIdentity,
                generation
        );

        AtomicReference<BackendControlSession> previous =
                new AtomicReference<>();

        sessions.compute(
                nonNullIdentity.serverName(),
                (serverName, existing) -> {
                    previous.set(existing);
                    return next;
                }
        );

        return new BackendControlSessionRegistration(
                next,
                previous.get()
        );
    }

    public boolean rollback(
            BackendControlSessionRegistration registration
    ) {
        BackendControlSessionRegistration nonNullRegistration =
                Objects.requireNonNull(
                        registration,
                        "registration cannot be null"
                );

        BackendControlSession current =
                nonNullRegistration.current();
        BackendControlSession previous =
                nonNullRegistration.previous();
        AtomicBoolean rolledBack = new AtomicBoolean();

        sessions.compute(
                current.identity().serverName(),
                (serverName, existing) -> {
                    if (!current.equals(existing)) {
                        return existing;
                    }

                    rolledBack.set(true);
                    return previous;
                }
        );

        return rolledBack.get();
    }

    public Optional<BackendControlSession> find(
            String backendName
    ) {
        String normalizedBackendName = requireBackendName(
                backendName
        );

        return Optional.ofNullable(
                sessions.get(normalizedBackendName)
        );
    }

    public boolean isCurrent(BackendControlSession session) {
        BackendControlSession nonNullSession =
                Objects.requireNonNull(
                        session,
                        "session cannot be null"
                );

        return nonNullSession.equals(
                sessions.get(
                        nonNullSession.identity().serverName()
                )
        );
    }

    public boolean removeIfCurrent(
            BackendControlSession expected
    ) {
        BackendControlSession nonNullExpected =
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                );

        return sessions.remove(
                nonNullExpected.identity().serverName(),
                nonNullExpected
        );
    }

    public Map<String, BackendControlSession> snapshot() {
        return Map.copyOf(sessions);
    }

    public int size() {
        return sessions.size();
    }

    public void clear() {
        sessions.clear();
    }

    private static String requireBackendName(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
        }

        return normalized;
    }
}

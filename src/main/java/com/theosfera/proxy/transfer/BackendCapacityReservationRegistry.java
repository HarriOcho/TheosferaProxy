package com.theosfera.proxy.transfer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BackendCapacityReservationRegistry {

    private final Map<UUID, BackendCapacityReservation> byRequest =
            new HashMap<>();
    private final Map<String, Integer> countsByBackend =
            new HashMap<>();

    public synchronized BackendCapacityReservationResult reserve(
            BackendCapacityReservation reservation,
            int connectedPlayers,
            int capacity
    ) {
        BackendCapacityReservation nonNullReservation =
                Objects.requireNonNull(
                        reservation,
                        "reservation cannot be null"
                );

        if (connectedPlayers < 0) {
            throw new IllegalArgumentException(
                    "connectedPlayers cannot be negative"
            );
        }

        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be greater than zero"
            );
        }

        BackendCapacityReservation existing =
                byRequest.get(nonNullReservation.requestId());

        if (existing != null) {
            return existing.equals(nonNullReservation)
                    ? BackendCapacityReservationResult.ALREADY_RESERVED
                    : BackendCapacityReservationResult.REQUEST_ID_CONFLICT;
        }

        int reserved = countsByBackend.getOrDefault(
                nonNullReservation.backendName(),
                0
        );

        if ((long) connectedPlayers + reserved >= capacity) {
            return BackendCapacityReservationResult.NO_CAPACITY;
        }

        byRequest.put(
                nonNullReservation.requestId(),
                nonNullReservation
        );

        countsByBackend.put(
                nonNullReservation.backendName(),
                Math.addExact(reserved, 1)
        );

        return BackendCapacityReservationResult.RESERVED;
    }

    public synchronized int reservedCount(String backendName) {
        return countsByBackend.getOrDefault(
                requireBackendName(backendName),
                0
        );
    }

    public synchronized Optional<BackendCapacityReservation>
    findByRequest(UUID requestId) {
        return Optional.ofNullable(
                byRequest.get(
                        Objects.requireNonNull(
                                requestId,
                                "requestId cannot be null"
                        )
                )
        );
    }

    public synchronized Optional<BackendCapacityReservation>
    removeByRequest(UUID requestId) {
        BackendCapacityReservation existing =
                byRequest.get(
                        Objects.requireNonNull(
                                requestId,
                                "requestId cannot be null"
                        )
                );

        return existing == null
                ? Optional.empty()
                : removeIfMatches(existing);
    }

    public synchronized Optional<BackendCapacityReservation>
    removeIfMatches(BackendCapacityReservation expected) {
        BackendCapacityReservation nonNullExpected =
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                );

        BackendCapacityReservation existing =
                byRequest.get(nonNullExpected.requestId());

        if (!nonNullExpected.equals(existing)) {
            return Optional.empty();
        }

        byRequest.remove(existing.requestId());

        countsByBackend.compute(
                existing.backendName(),
                (ignored, count) -> {
                    if (count == null || count <= 1) {
                        return null;
                    }

                    return count - 1;
                }
        );

        return Optional.of(existing);
    }

    public synchronized Map<UUID, BackendCapacityReservation>
    snapshot() {
        return Map.copyOf(byRequest);
    }

    public synchronized void clear() {
        byRequest.clear();
        countsByBackend.clear();
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

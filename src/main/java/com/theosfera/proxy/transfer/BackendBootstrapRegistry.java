package com.theosfera.proxy.transfer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BackendBootstrapRegistry {

    private static final Duration DEFAULT_EXPIRATION =
            Duration.ofSeconds(30);

    private final long expirationMillis;

    private final Map<String, BackendBootstrapReservation>
            reservationsByTarget = new HashMap<>();

    private final Map<UUID, BackendBootstrapReservation>
            reservationsByRequest = new HashMap<>();

    public BackendBootstrapRegistry() {
        this(DEFAULT_EXPIRATION);
    }

    public BackendBootstrapRegistry(Duration expiration) {
        Duration nonNullExpiration =
                Objects.requireNonNull(
                        expiration,
                        "expiration cannot be null"
                );

        if (nonNullExpiration.isZero()
                || nonNullExpiration.isNegative()) {
            throw new IllegalArgumentException(
                    "expiration must be greater than zero"
            );
        }

        expirationMillis =
                nonNullExpiration.toMillis();
    }

    public synchronized BackendBootstrapRegistrationResult
    register(
            BackendBootstrapReservation reservation
    ) {
        BackendBootstrapReservation nonNullReservation =
                Objects.requireNonNull(
                        reservation,
                        "reservation cannot be null"
                );

        BackendBootstrapReservation requestReservation =
                reservationsByRequest.get(
                        nonNullReservation.requestId()
                );

        if (requestReservation != null) {
            if (requestReservation.equals(nonNullReservation)) {
                return BackendBootstrapRegistrationResult
                        .ALREADY_RESERVED;
            }

            return BackendBootstrapRegistrationResult
                    .REQUEST_ID_CONFLICT;
        }

        BackendBootstrapReservation targetReservation =
                reservationsByTarget.get(
                        nonNullReservation.targetBackendName()
                );

        if (targetReservation != null) {
            if (targetReservation.equals(nonNullReservation)) {
                return BackendBootstrapRegistrationResult
                        .ALREADY_RESERVED;
            }

            if (!isExpired(
                    targetReservation,
                    nonNullReservation.createdAt()
            )) {
                return BackendBootstrapRegistrationResult
                        .TARGET_BUSY;
            }

            removeInternal(targetReservation);
        }

        reservationsByTarget.put(
                nonNullReservation.targetBackendName(),
                nonNullReservation
        );

        reservationsByRequest.put(
                nonNullReservation.requestId(),
                nonNullReservation
        );

        return BackendBootstrapRegistrationResult.RESERVED;
    }

    public synchronized Optional<BackendBootstrapReservation>
    findByTarget(
            String targetBackendName
    ) {
        Objects.requireNonNull(
                targetBackendName,
                "targetBackendName cannot be null"
        );

        return Optional.ofNullable(
                reservationsByTarget.get(targetBackendName)
        );
    }

    public synchronized Optional<BackendBootstrapReservation>
    findByRequest(
            UUID requestId
    ) {
        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );

        return Optional.ofNullable(
                reservationsByRequest.get(requestId)
        );
    }

    public synchronized Optional<BackendBootstrapReservation>
    removeByRequest(
            UUID requestId
    ) {
        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );

        BackendBootstrapReservation removed =
                reservationsByRequest.remove(requestId);

        if (removed == null) {
            return Optional.empty();
        }

        reservationsByTarget.remove(
                removed.targetBackendName(),
                removed
        );

        return Optional.of(removed);
    }

    public synchronized Optional<BackendBootstrapReservation>
    removeByTarget(
            String targetBackendName
    ) {
        Objects.requireNonNull(
                targetBackendName,
                "targetBackendName cannot be null"
        );

        BackendBootstrapReservation removed =
                reservationsByTarget.remove(
                        targetBackendName
                );

        if (removed == null) {
            return Optional.empty();
        }

        reservationsByRequest.remove(
                removed.requestId(),
                removed
        );

        return Optional.of(removed);
    }

    public synchronized int size() {
        return reservationsByRequest.size();
    }

    public synchronized void clear() {
        reservationsByTarget.clear();
        reservationsByRequest.clear();
    }

    private boolean isExpired(
            BackendBootstrapReservation reservation,
            long currentTime
    ) {
        if (currentTime <= reservation.createdAt()) {
            return false;
        }

        return currentTime - reservation.createdAt()
                >= expirationMillis;
    }

    private void removeInternal(
            BackendBootstrapReservation reservation
    ) {
        reservationsByTarget.remove(
                reservation.targetBackendName(),
                reservation
        );

        reservationsByRequest.remove(
                reservation.requestId(),
                reservation
        );
    }
}

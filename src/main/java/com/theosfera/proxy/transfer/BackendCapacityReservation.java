package com.theosfera.proxy.transfer;

import java.util.Objects;
import java.util.UUID;

public record BackendCapacityReservation(
        UUID requestId,
        UUID playerId,
        String backendName
) {

    public BackendCapacityReservation {
        Objects.requireNonNull(
                requestId,
                "requestId cannot be null"
        );
        Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        backendName = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();

        if (backendName.isEmpty()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
        }
    }
}

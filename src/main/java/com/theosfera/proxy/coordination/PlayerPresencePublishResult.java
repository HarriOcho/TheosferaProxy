package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.Optional;

public record PlayerPresencePublishResult(
        Status status,
        DistributedPlayerPresence presence
) {

    public enum Status {
        RECORDED,
        UPDATED,
        ALREADY_RECORDED,
        STALE,
        CONFLICT,
        SESSION_NOT_FOUND,
        NOT_SESSION_OWNER,
        COORDINATION_UNAVAILABLE
    }

    public PlayerPresencePublishResult {
        Objects.requireNonNull(status, "status cannot be null");

        boolean requiresPresence = switch (status) {
            case RECORDED, UPDATED, ALREADY_RECORDED -> true;
            default -> false;
        };

        if (requiresPresence && presence == null) {
            throw new IllegalArgumentException(
                    "successful presence result requires presence"
            );
        }
        if (!requiresPresence && presence != null) {
            throw new IllegalArgumentException(
                    "unsuccessful presence result cannot contain presence"
            );
        }
    }

    public static PlayerPresencePublishResult withPresence(
            Status status,
            DistributedPlayerPresence presence
    ) {
        return new PlayerPresencePublishResult(status, presence);
    }

    public static PlayerPresencePublishResult withoutPresence(
            Status status
    ) {
        return new PlayerPresencePublishResult(status, null);
    }

    public Optional<DistributedPlayerPresence> publishedPresence() {
        return Optional.ofNullable(presence);
    }
}

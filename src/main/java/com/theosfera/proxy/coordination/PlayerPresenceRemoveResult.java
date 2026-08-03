package com.theosfera.proxy.coordination;

import java.util.Objects;

public record PlayerPresenceRemoveResult(Status status) {

    public enum Status {
        REMOVED,
        NOT_FOUND,
        STALE,
        CONFLICT,
        SESSION_NOT_FOUND,
        NOT_SESSION_OWNER,
        COORDINATION_UNAVAILABLE
    }

    public PlayerPresenceRemoveResult {
        Objects.requireNonNull(status, "status cannot be null");
    }
}

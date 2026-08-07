package com.theosfera.proxy.coordination;

import java.util.Objects;

public record BackendBootstrapReleaseResult(
        Status status
) {

    public enum Status {
        RELEASED,
        NOT_FOUND,
        NOT_OWNER,
        CONFLICT,
        MEMBERSHIP_NOT_FOUND,
        NOT_MEMBERSHIP_OWNER,
        COORDINATION_UNAVAILABLE
    }

    public BackendBootstrapReleaseResult {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );
    }

    public boolean released() {
        return status == Status.RELEASED;
    }
}

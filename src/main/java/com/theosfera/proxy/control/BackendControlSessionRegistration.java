package com.theosfera.proxy.control;

import java.util.Objects;
import java.util.Optional;

public record BackendControlSessionRegistration(
        BackendControlSession current,
        BackendControlSession previous
) {

    public BackendControlSessionRegistration {
        Objects.requireNonNull(
                current,
                "current cannot be null"
        );
    }

    public Optional<BackendControlSession> previousOptional() {
        return Optional.ofNullable(previous);
    }

    public boolean replacedExistingSession() {
        return previous != null;
    }
}

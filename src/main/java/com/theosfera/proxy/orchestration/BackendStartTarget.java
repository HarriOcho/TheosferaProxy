package com.theosfera.proxy.orchestration;

import java.util.Objects;
import java.util.regex.Pattern;

public record BackendStartTarget(
        String backendName,
        String targetReference
) {

    private static final Pattern BACKEND_NAME_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
            );

    private static final int MAX_TARGET_REFERENCE_LENGTH = 256;

    public BackendStartTarget {
        backendName = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();
        targetReference = Objects.requireNonNull(
                targetReference,
                "targetReference cannot be null"
        ).trim();

        if (!BACKEND_NAME_PATTERN.matcher(backendName).matches()) {
            throw new IllegalArgumentException(
                    "backendName must be a valid backend name"
            );
        }

        if (targetReference.isEmpty()) {
            throw new IllegalArgumentException(
                    "targetReference cannot be blank"
            );
        }

        if (targetReference.length() > MAX_TARGET_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(
                    "targetReference cannot be longer than "
                            + MAX_TARGET_REFERENCE_LENGTH
                            + " characters"
            );
        }

        if (targetReference.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "targetReference cannot contain control characters"
            );
        }
    }
}

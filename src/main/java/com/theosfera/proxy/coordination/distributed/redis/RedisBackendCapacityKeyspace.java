package com.theosfera.proxy.coordination.distributed.redis;

import java.util.Objects;
import java.util.UUID;

public final class RedisBackendCapacityKeyspace {

    private static final String DEFAULT_NAMESPACE =
            "theosfera:coordination";

    private final String namespace;

    public RedisBackendCapacityKeyspace(String namespace) {
        this.namespace = Objects.requireNonNull(
                namespace,
                "namespace cannot be null"
        ).trim();
        if (this.namespace.isEmpty()) {
            throw new IllegalArgumentException(
                    "namespace cannot be blank"
            );
        }
    }

    public static RedisBackendCapacityKeyspace defaultKeyspace() {
        return new RedisBackendCapacityKeyspace(DEFAULT_NAMESPACE);
    }

    public String reservationKey(UUID requestId) {
        return namespace + ":backend-capacity:reservation:"
                + Objects.requireNonNull(requestId, "requestId cannot be null");
    }

    public String backendReservationsKey(String backendName) {
        return namespace + ":backend-capacity:backend:"
                + requireBackendName(backendName);
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

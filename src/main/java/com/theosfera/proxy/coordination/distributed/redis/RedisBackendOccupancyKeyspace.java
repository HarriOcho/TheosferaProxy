package com.theosfera.proxy.coordination.distributed.redis;

import java.util.Objects;

public final class RedisBackendOccupancyKeyspace {

    private static final String DEFAULT_NAMESPACE =
            "theosfera:coordination";

    private final String namespace;

    public RedisBackendOccupancyKeyspace(String namespace) {
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

    public static RedisBackendOccupancyKeyspace defaultKeyspace() {
        return new RedisBackendOccupancyKeyspace(DEFAULT_NAMESPACE);
    }

    public String backendPresenceIndexPrefix() {
        return namespace + ":backend-presence:";
    }

    public String backendPresenceIndexKey(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
        }

        return backendPresenceIndexPrefix() + normalized;
    }
}

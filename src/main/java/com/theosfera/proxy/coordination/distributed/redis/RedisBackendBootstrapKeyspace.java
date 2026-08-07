package com.theosfera.proxy.coordination.distributed.redis;

import java.util.Objects;
import java.util.UUID;

public final class RedisBackendBootstrapKeyspace {

    private static final String DEFAULT_NAMESPACE =
            "theosfera:coordination";

    private final String namespace;

    public RedisBackendBootstrapKeyspace(String namespace) {
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

    public static RedisBackendBootstrapKeyspace defaultKeyspace() {
        return new RedisBackendBootstrapKeyspace(DEFAULT_NAMESPACE);
    }

    public String leaseKey(String backendName) {
        return namespace
                + ":backend-bootstrap:lease:"
                + requireBackendName(backendName);
    }

    public String requestKey(UUID requestId) {
        return namespace
                + ":backend-bootstrap:request:"
                + Objects.requireNonNull(
                        requestId,
                        "requestId cannot be null"
                );
    }

    public String fencingCounterKey() {
        return namespace + ":backend-bootstrap:fencing";
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

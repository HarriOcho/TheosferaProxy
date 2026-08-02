package com.theosfera.proxy.coordination.distributed.redis;

import java.util.Objects;
import java.util.UUID;

public final class RedisPlayerSessionKeyspace {

    private static final String DEFAULT_NAMESPACE =
            "theosfera:coordination";

    private final String namespace;

    public RedisPlayerSessionKeyspace(String namespace) {
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

    public static RedisPlayerSessionKeyspace defaultKeyspace() {
        return new RedisPlayerSessionKeyspace(
                DEFAULT_NAMESPACE
        );
    }

    public String playerSessionKey(UUID playerId) {
        return namespace
                + ":player-session:"
                + Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                );
    }

    public String fencingCounterKey() {
        return namespace + ":player-session:fencing";
    }
}

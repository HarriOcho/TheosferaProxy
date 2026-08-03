package com.theosfera.proxy.coordination.distributed.redis;

import java.util.Objects;
import java.util.UUID;

public final class RedisPlayerPresenceKeyspace {

    private static final String DEFAULT_NAMESPACE =
            "theosfera:coordination";

    private final String namespace;

    public RedisPlayerPresenceKeyspace(String namespace) {
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

    public static RedisPlayerPresenceKeyspace defaultKeyspace() {
        return new RedisPlayerPresenceKeyspace(DEFAULT_NAMESPACE);
    }

    public String playerPresenceKey(UUID playerId) {
        return namespace
                + ":player-presence:"
                + Objects.requireNonNull(
                        playerId,
                        "playerId cannot be null"
                );
    }
}

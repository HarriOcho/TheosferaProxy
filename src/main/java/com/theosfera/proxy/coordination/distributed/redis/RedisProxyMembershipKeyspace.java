package com.theosfera.proxy.coordination.distributed.redis;

import java.util.Objects;

public final class RedisProxyMembershipKeyspace {

    private static final String DEFAULT_NAMESPACE =
            "theosfera:coordination";

    private final String namespace;

    public RedisProxyMembershipKeyspace(String namespace) {
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

    public static RedisProxyMembershipKeyspace defaultKeyspace() {
        return new RedisProxyMembershipKeyspace(DEFAULT_NAMESPACE);
    }

    public String membershipKey(String proxyName) {
        return namespace
                + ":proxy-membership:"
                + Objects.requireNonNull(
                        proxyName,
                        "proxyName cannot be null"
                );
    }

    public String fencingCounterKey() {
        return namespace + ":proxy-membership:fencing";
    }
}

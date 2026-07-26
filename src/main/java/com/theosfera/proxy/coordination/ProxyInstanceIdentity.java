package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.UUID;

public record ProxyInstanceIdentity(
        String proxyName,
        UUID incarnationId
) {

    public ProxyInstanceIdentity {
        proxyName = Objects.requireNonNull(
                proxyName,
                "proxyName cannot be null"
        ).trim();

        if (proxyName.isEmpty()) {
            throw new IllegalArgumentException(
                    "proxyName cannot be blank"
            );
        }

        Objects.requireNonNull(
                incarnationId,
                "incarnationId cannot be null"
        );
    }
}

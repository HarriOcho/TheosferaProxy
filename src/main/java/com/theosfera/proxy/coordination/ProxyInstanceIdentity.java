package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ProxyInstanceIdentity(
        String proxyName,
        UUID incarnationId
) {

    private static final int MAX_PROXY_NAME_LENGTH = 32;

    private static final Pattern SAFE_PROXY_NAME =
            Pattern.compile(
                    "[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?"
            );

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

        if (proxyName.length() > MAX_PROXY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "proxyName cannot be longer than "
                            + MAX_PROXY_NAME_LENGTH
                            + " characters"
            );
        }

        if (!SAFE_PROXY_NAME.matcher(proxyName).matches()) {
            throw new IllegalArgumentException(
                    "proxyName must use lowercase letters, "
                            + "numbers and hyphens"
            );
        }

        Objects.requireNonNull(
                incarnationId,
                "incarnationId cannot be null"
        );
    }
}

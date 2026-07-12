package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendHelloPayload;
import com.theosfera.protocol.message.payload.BackendType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BackendAuthorizationPolicy {

    private final Map<String, BackendType> allowedBackends;

    public BackendAuthorizationPolicy(
            Map<String, BackendType> allowedBackends
    ) {
        Objects.requireNonNull(
                allowedBackends,
                "allowedBackends cannot be null"
        );

        Map<String, BackendType> validated =
                new LinkedHashMap<>();

        allowedBackends.forEach((serverName, backendType) -> {
            BackendIdentity identity =
                    new BackendIdentity(
                            serverName,
                            backendType
                    );

            BackendType previous = validated.putIfAbsent(
                    identity.serverName(),
                    identity.backendType()
            );

            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate authorized backend: "
                                + identity.serverName()
                );
            }
        });

        this.allowedBackends = Map.copyOf(validated);
    }

    public Optional<BackendIdentity> authorize(
            String sourceServerName,
            BackendHelloPayload helloPayload
    ) {
        Objects.requireNonNull(
                sourceServerName,
                "sourceServerName cannot be null"
        );
        Objects.requireNonNull(
                helloPayload,
                "helloPayload cannot be null"
        );

        if (!sourceServerName.equals(
                helloPayload.backendName()
        )) {
            return Optional.empty();
        }

        BackendType expectedType =
                allowedBackends.get(sourceServerName);

        if (expectedType != helloPayload.backendType()) {
            return Optional.empty();
        }

        return Optional.of(
                new BackendIdentity(
                        sourceServerName,
                        expectedType
                )
        );
    }

    public Map<String, BackendType> allowedBackends() {
        return allowedBackends;
    }
}
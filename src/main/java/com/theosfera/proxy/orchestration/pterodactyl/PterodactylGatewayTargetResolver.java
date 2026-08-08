package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.proxy.orchestration.BackendStartTarget;
import com.theosfera.proxy.orchestration.BackendStartTargetResolver;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PterodactylGatewayTargetResolver
        implements BackendStartTargetResolver {

    private final Map<String, String> targets;

    public PterodactylGatewayTargetResolver(
            PterodactylGatewayConfig config
    ) {
        this.targets = Objects.requireNonNull(
                config,
                "config cannot be null"
        ).targets();
    }

    @Override
    public Optional<BackendStartTarget> resolve(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "backendName cannot be blank"
            );
        }

        String targetReference = targets.get(normalized);
        if (targetReference == null) {
            return Optional.empty();
        }

        return Optional.of(
                new BackendStartTarget(
                        normalized,
                        targetReference
                )
        );
    }
}

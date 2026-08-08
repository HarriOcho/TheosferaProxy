package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.proxy.orchestration.BackendStartTarget;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PterodactylGatewayConfig(
        boolean enabled,
        URI gatewayUri,
        Duration requestTimeout,
        String tokenEnvironmentVariable,
        Map<String, String> targets
) {

    public PterodactylGatewayConfig {
        gatewayUri = requireGatewayUri(gatewayUri, enabled);
        requestTimeout = requirePositive(requestTimeout);
        tokenEnvironmentVariable = requireText(
                tokenEnvironmentVariable,
                "tokenEnvironmentVariable"
        );
        targets = validateTargets(targets);

        if (enabled && targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "enabled Pterodactyl gateway requires at least one target"
            );
        }
    }

    public URI startEndpoint() {
        String base = gatewayUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/v1/backend-start");
    }

    private static URI requireGatewayUri(
            URI gatewayUri,
            boolean enabled
    ) {
        URI uri = Objects.requireNonNull(
                gatewayUri,
                "gatewayUri cannot be null"
        ).normalize();

        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "gatewayUri must be an absolute network URI"
            );
        }
        if (enabled && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(
                    "enabled Pterodactyl gateway requires HTTPS"
            );
        }
        if (uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "gatewayUri cannot contain user-info, query or fragment"
            );
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException(
                    "gatewayUri must not contain an application path"
            );
        }
        return uri;
    }

    private static Duration requirePositive(Duration timeout) {
        Duration nonNullTimeout = Objects.requireNonNull(
                timeout,
                "requestTimeout cannot be null"
        );
        if (nonNullTimeout.isZero()
                || nonNullTimeout.isNegative()
                || nonNullTimeout.toMillis() <= 0L) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive and at least one millisecond"
            );
        }
        return nonNullTimeout;
    }

    private static Map<String, String> validateTargets(
            Map<String, String> targets
    ) {
        Objects.requireNonNull(targets, "targets cannot be null");

        Map<String, String> validated = new LinkedHashMap<>();
        Set<String> references = new HashSet<>();

        targets.forEach((backendName, targetReference) -> {
            BackendStartTarget target = new BackendStartTarget(
                    backendName,
                    targetReference
            );
            if (validated.putIfAbsent(
                    target.backendName(),
                    target.targetReference()
            ) != null) {
                throw new IllegalArgumentException(
                        "duplicate backend target: " + target.backendName()
                );
            }
            if (!references.add(target.targetReference())) {
                throw new IllegalArgumentException(
                        "duplicate Pterodactyl target reference"
                );
            }
        });

        return Map.copyOf(validated);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(
                value,
                name + " cannot be null"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " cannot contain control characters"
            );
        }
        return normalized;
    }
}

package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendPolicyEntry;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public final class PterodactylGatewayConfigLoader {

    public static final String FILE_NAME = "orchestration.properties";

    private static final String TARGET_PREFIX = "target.";
    private static final Set<String> FIXED_KEYS = Set.of(
            "enabled",
            "gateway-uri",
            "request-timeout-seconds",
            "gateway-token-env"
    );
    private static final String DEFAULT_CONFIG = """
            # Fenced backend orchestration through the Theosfera Gateway.
            # Keep disabled until the Gateway is deployed and targets are mapped.
            enabled=false
            gateway-uri=https://127.0.0.1:25610
            request-timeout-seconds=5
            gateway-token-env=THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN
            # target.lobby-1=<pterodactyl-server-reference>
            """;

    private final Path configFile;
    private final BackendAuthorizationPolicy authorizationPolicy;

    public PterodactylGatewayConfigLoader(
            Path dataDirectory,
            BackendAuthorizationPolicy authorizationPolicy
    ) {
        Path directory = Objects.requireNonNull(
                dataDirectory,
                "dataDirectory cannot be null"
        ).normalize();
        this.configFile = directory.resolve(FILE_NAME);
        this.authorizationPolicy = Objects.requireNonNull(
                authorizationPolicy,
                "authorizationPolicy cannot be null"
        );
    }

    public PterodactylGatewayConfig load() {
        createDefaultConfigIfMissing();

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                configFile,
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load Pterodactyl orchestration configuration",
                    exception
            );
        }

        rejectUnknownProperties(properties);

        boolean enabled = parseBoolean(
                required(properties, "enabled"),
                "enabled"
        );
        URI gatewayUri = parseUri(
                required(properties, "gateway-uri"),
                "gateway-uri"
        );
        long timeoutSeconds = parseLong(
                required(properties, "request-timeout-seconds"),
                "request-timeout-seconds"
        );
        String tokenEnvironmentVariable = required(
                properties,
                "gateway-token-env"
        );
        Map<String, String> targets = readTargets(properties);

        return new PterodactylGatewayConfig(
                enabled,
                gatewayUri,
                Duration.ofSeconds(timeoutSeconds),
                tokenEnvironmentVariable,
                targets
        );
    }

    public Path configFile() {
        return configFile;
    }

    private Map<String, String> readTargets(Properties properties) {
        Map<String, String> targets = new LinkedHashMap<>();
        Map<String, BackendPolicyEntry> policyEntries =
                authorizationPolicy.backendEntries();

        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(TARGET_PREFIX))
                .sorted()
                .forEach(key -> {
                    String backendName = key.substring(TARGET_PREFIX.length())
                            .trim();
                    if (backendName.isEmpty()) {
                        throw new IllegalStateException(
                                "Pterodactyl target property requires backend name"
                        );
                    }

                    BackendPolicyEntry policyEntry = policyEntries.get(backendName);
                    if (policyEntry == null) {
                        throw new IllegalStateException(
                                "Pterodactyl target references unauthorized backend: "
                                        + backendName
                        );
                    }
                    if (policyEntry.backendType() == BackendType.AUTH) {
                        throw new IllegalStateException(
                                "AUTH backend cannot be configured as ordinary cold-start target: "
                                        + backendName
                        );
                    }

                    String targetReference = required(properties, key);
                    String previous = targets.putIfAbsent(
                            backendName,
                            targetReference
                    );
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate Pterodactyl backend target: "
                                        + backendName
                        );
                    }
                });

        return Map.copyOf(targets);
    }

    private static void rejectUnknownProperties(Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (!FIXED_KEYS.contains(key) && !key.startsWith(TARGET_PREFIX)) {
                throw new IllegalStateException(
                        "Unknown Pterodactyl orchestration property: " + key
                );
            }
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing Pterodactyl orchestration property: " + key
            );
        }
        return value.trim();
    }

    private static boolean parseBoolean(String raw, String key) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(
                    "Invalid boolean Pterodactyl orchestration property: " + key
            );
        };
    }

    private static long parseLong(String raw, String key) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Invalid long Pterodactyl orchestration property: " + key,
                    exception
            );
        }
    }

    private static URI parseUri(String raw, String key) {
        try {
            return URI.create(raw.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid URI Pterodactyl orchestration property: " + key,
                    exception
            );
        }
    }

    private void createDefaultConfigIfMissing() {
        if (Files.exists(configFile)) {
            return;
        }

        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(
                    configFile,
                    DEFAULT_CONFIG,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create default Pterodactyl orchestration configuration",
                    exception
            );
        }
    }
}

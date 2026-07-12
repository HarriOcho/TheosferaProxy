package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class BackendPolicyConfigLoader {

    public static final String FILE_NAME =
            "backends.properties";

    private static final String DEFAULT_CONFIG = """
            # Backends autorizados para TheosferaProtocol.
            # Formato: nombre-en-velocity=TIPO
            auth-1=AUTH
            lobby-1=LOBBY
            skyblock-1=SKYBLOCK
            """;

    private final Path configFile;

    public BackendPolicyConfigLoader(Path dataDirectory) {
        Objects.requireNonNull(
                dataDirectory,
                "dataDirectory cannot be null"
        );

        this.configFile = dataDirectory.resolve(FILE_NAME);
    }

    public BackendAuthorizationPolicy load() {
        createDefaultConfigIfMissing();

        Properties properties = new Properties();

        try (Reader reader = Files.newBufferedReader(
                configFile,
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load backend policy configuration",
                    exception
            );
        }

        Map<String, BackendType> allowedBackends =
                new LinkedHashMap<>();

        for (String rawServerName
                : properties.stringPropertyNames()) {
            String serverName = rawServerName.trim();
            String rawType = properties
                    .getProperty(rawServerName)
                    .trim()
                    .toUpperCase(Locale.ROOT);

            final BackendType backendType;

            try {
                backendType = BackendType.valueOf(rawType);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "Invalid backend type for "
                                + serverName
                                + ": "
                                + rawType,
                        exception
                );
            }

            BackendType previous = allowedBackends.putIfAbsent(
                    serverName,
                    backendType
            );

            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate backend configuration: "
                                + serverName
                );
            }
        }

        if (allowedBackends.isEmpty()) {
            throw new IllegalStateException(
                    "Backend policy configuration cannot be empty"
            );
        }

        return new BackendAuthorizationPolicy(
                allowedBackends
        );
    }

    public Path configFile() {
        return configFile;
    }

    private void createDefaultConfigIfMissing() {
        if (Files.exists(configFile)) {
            return;
        }

        try {
            Files.createDirectories(
                    configFile.getParent()
            );
            Files.writeString(
                    configFile,
                    DEFAULT_CONFIG,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create default backend "
                            + "policy configuration",
                    exception
            );
        }
    }
}
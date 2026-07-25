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
            # Formato: nombre-en-velocity=TIPO,capacidad,preferencia
            auth-1=AUTH,1,100
            lobby-1=LOBBY,100,90
            skyblock-1=SKYBLOCK,200,80
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

        Map<String, BackendPolicyEntry> backendEntries =
                new LinkedHashMap<>();

        for (String rawServerName
                : properties.stringPropertyNames()) {
            String serverName = rawServerName.trim();
            String rawEntry = properties
                    .getProperty(rawServerName)
                    .trim();

            String[] fields = rawEntry.split(",", -1);

            if (fields.length != 3) {
                throw new IllegalStateException(
                        "Invalid backend configuration for "
                                + serverName
                                + ": expected "
                                + "TIPO,capacidad,preferencia"
                );
            }

            String rawType = fields[0]
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

            final int capacity;
            final int preference;

            try {
                capacity = Integer.parseInt(
                        fields[1].trim()
                );
                preference = Integer.parseInt(
                        fields[2].trim()
                );
            } catch (NumberFormatException exception) {
                throw new IllegalStateException(
                        "Invalid numeric backend configuration for "
                                + serverName
                                + ": "
                                + rawEntry,
                        exception
                );
            }

            final BackendPolicyEntry entry;

            try {
                entry = new BackendPolicyEntry(
                        backendType,
                        capacity,
                        preference
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "Invalid backend configuration for "
                                + serverName
                                + ": "
                                + rawEntry,
                        exception
                );
            }

            BackendPolicyEntry previous =
                    backendEntries.putIfAbsent(
                            serverName,
                            entry
                    );

            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate backend configuration: "
                                + serverName
                );
            }
        }

        if (backendEntries.isEmpty()) {
            throw new IllegalStateException(
                    "Backend policy configuration cannot be empty"
            );
        }

        return new BackendAuthorizationPolicy(
                backendEntries
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

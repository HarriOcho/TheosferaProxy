package com.theosfera.proxy.control;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class BackendControlConfigLoader {

    public static final String FILE_NAME = "control.properties";

    private static final String DEFAULT_CONFIG = """
            # Secure backend control channel.
            # Keep disabled until the PKCS12 keystore and HMAC secrets are provisioned.
            enabled=false
            bind-host=127.0.0.1
            bind-port=25590
            authentication-timeout-seconds=5
            keystore-path=control-server.p12
            keystore-password-env=THEOSFERA_CONTROL_KEYSTORE_PASSWORD
            secrets-file=control-secrets.properties
            """;

    private final Path dataDirectory;
    private final Path configFile;

    public BackendControlConfigLoader(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(
                dataDirectory,
                "dataDirectory cannot be null"
        ).normalize();
        this.configFile = this.dataDirectory.resolve(FILE_NAME);
    }

    public BackendControlConfig load() {
        createDefaultConfigIfMissing();

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                configFile,
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load backend control configuration",
                    exception
            );
        }

        boolean enabled = parseBoolean(
                required(properties, "enabled"),
                "enabled"
        );
        String bindHost = required(properties, "bind-host");
        int bindPort = parseInt(
                required(properties, "bind-port"),
                "bind-port"
        );
        long timeoutSeconds = parseLong(
                required(
                        properties,
                        "authentication-timeout-seconds"
                ),
                "authentication-timeout-seconds"
        );
        String passwordEnvironmentVariable = required(
                properties,
                "keystore-password-env"
        );

        return new BackendControlConfig(
                enabled,
                bindHost,
                bindPort,
                Duration.ofSeconds(timeoutSeconds),
                resolvePath(required(properties, "keystore-path")),
                passwordEnvironmentVariable,
                resolvePath(required(properties, "secrets-file"))
        );
    }

    public Path configFile() {
        return configFile;
    }

    private Path resolvePath(String rawPath) {
        Path configured = Path.of(rawPath.trim());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return dataDirectory.resolve(configured).normalize();
    }

    private static String required(
            Properties properties,
            String key
    ) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing backend control configuration property: "
                            + key
            );
        }
        return value.trim();
    }

    private static boolean parseBoolean(
            String raw,
            String key
    ) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(
                    "Invalid boolean backend control configuration for "
                            + key
            );
        };
    }

    private static int parseInt(
            String raw,
            String key
    ) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Invalid integer backend control configuration for "
                            + key,
                    exception
            );
        }
    }

    private static long parseLong(
            String raw,
            String key
    ) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Invalid long backend control configuration for "
                            + key,
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
                    "Could not create default backend control configuration",
                    exception
            );
        }
    }
}

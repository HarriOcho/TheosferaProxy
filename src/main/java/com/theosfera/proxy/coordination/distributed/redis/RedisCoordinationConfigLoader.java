package com.theosfera.proxy.coordination.distributed.redis;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

public final class RedisCoordinationConfigLoader {

    public static final String FILE_NAME =
            "redis-coordination.properties";

    private static final String REDIS_URI_PROPERTY = "redis-uri";
    private static final String MEMBERSHIP_TTL_SECONDS_PROPERTY =
            "membership-ttl-seconds";
    private static final String MEMBERSHIP_RENEW_SECONDS_PROPERTY =
            "membership-renew-seconds";
    private static final String PLAYER_SESSION_TTL_SECONDS_PROPERTY =
            "player-session-ttl-seconds";
    private static final String PLAYER_SESSION_RENEW_SECONDS_PROPERTY =
            "player-session-renew-seconds";
    private static final long DEFAULT_PLAYER_SESSION_TTL_SECONDS = 30L;
    private static final long DEFAULT_PLAYER_SESSION_RENEW_SECONDS = 10L;

    private static final String DEFAULT_CONFIG = """
            # Redis compartido para coordinacion distribuida entre proxies.
            redis-uri=redis://127.0.0.1:6379

            # Lease autoritativo de membresia del proxy.
            membership-ttl-seconds=15

            # Debe ser menor que membership-ttl-seconds.
            membership-renew-seconds=5

            # Lease autoritativo de sesion autenticada del jugador.
            player-session-ttl-seconds=30

            # Debe ser menor que player-session-ttl-seconds.
            player-session-renew-seconds=10
            """;

    private final Path configFile;

    public RedisCoordinationConfigLoader(Path dataDirectory) {
        Objects.requireNonNull(
                dataDirectory,
                "dataDirectory cannot be null"
        );
        this.configFile = dataDirectory.resolve(FILE_NAME);
    }

    public RedisCoordinationConfig load() {
        createDefaultConfigIfMissing();

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                configFile,
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load Redis coordination configuration",
                    exception
            );
        }

        String redisUri = required(properties, REDIS_URI_PROPERTY);
        long membershipTtlSeconds = positiveLong(
                properties,
                MEMBERSHIP_TTL_SECONDS_PROPERTY
        );
        long membershipRenewSeconds = positiveLong(
                properties,
                MEMBERSHIP_RENEW_SECONDS_PROPERTY
        );
        long playerSessionTtlSeconds = positiveLongOrDefault(
                properties,
                PLAYER_SESSION_TTL_SECONDS_PROPERTY,
                DEFAULT_PLAYER_SESSION_TTL_SECONDS
        );
        long playerSessionRenewSeconds = positiveLongOrDefault(
                properties,
                PLAYER_SESSION_RENEW_SECONDS_PROPERTY,
                DEFAULT_PLAYER_SESSION_RENEW_SECONDS
        );

        try {
            return new RedisCoordinationConfig(
                    redisUri,
                    Duration.ofSeconds(membershipTtlSeconds),
                    Duration.ofSeconds(membershipRenewSeconds),
                    Duration.ofSeconds(playerSessionTtlSeconds),
                    Duration.ofSeconds(playerSessionRenewSeconds)
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Redis coordination configuration is invalid",
                    exception
            );
        }
    }

    public Path configFile() {
        return configFile;
    }

    private String required(Properties properties, String key) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            throw new IllegalStateException(
                    "Redis coordination configuration requires " + key
            );
        }

        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "Redis coordination configuration " + key
                            + " cannot be blank"
            );
        }
        return value;
    }

    private long positiveLong(Properties properties, String key) {
        return parsePositiveLong(required(properties, key), key);
    }

    private long positiveLongOrDefault(
            Properties properties,
            String key,
            long defaultValue
    ) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }
        return parsePositiveLong(required(properties, key), key);
    }

    private long parsePositiveLong(String raw, String key) {
        final long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Redis coordination configuration " + key
                            + " must be numeric",
                    exception
            );
        }

        if (value <= 0) {
            throw new IllegalStateException(
                    "Redis coordination configuration " + key
                            + " must be positive"
            );
        }
        return value;
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
                    "Could not create default Redis coordination configuration",
                    exception
            );
        }
    }
}

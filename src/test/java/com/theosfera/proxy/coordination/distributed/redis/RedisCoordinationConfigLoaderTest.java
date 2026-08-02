package com.theosfera.proxy.coordination.distributed.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisCoordinationConfigLoaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createsAndLoadsSafeDefaults() {
        RedisCoordinationConfigLoader loader =
                new RedisCoordinationConfigLoader(tempDirectory);

        RedisCoordinationConfig config = loader.load();

        assertEquals("redis://127.0.0.1:6379", config.redisUri());
        assertEquals(Duration.ofSeconds(15), config.membershipTtl());
        assertEquals(
                Duration.ofSeconds(5),
                config.membershipRenewInterval()
        );
        assertEquals(Duration.ofSeconds(30), config.playerSessionTtl());
        assertEquals(
                Duration.ofSeconds(10),
                config.playerSessionRenewInterval()
        );
    }

    @Test
    void rejectsMembershipRenewIntervalNotShorterThanTtl()
            throws Exception {
        Path configFile = tempDirectory.resolve(
                RedisCoordinationConfigLoader.FILE_NAME
        );
        Files.writeString(
                configFile,
                configText(15, 15, 30, 10)
        );

        RedisCoordinationConfigLoader loader =
                new RedisCoordinationConfigLoader(tempDirectory);

        assertThrows(IllegalStateException.class, loader::load);
    }

    @Test
    void rejectsPlayerSessionRenewIntervalNotShorterThanTtl()
            throws Exception {
        Path configFile = tempDirectory.resolve(
                RedisCoordinationConfigLoader.FILE_NAME
        );
        Files.writeString(
                configFile,
                configText(15, 5, 30, 30)
        );

        RedisCoordinationConfigLoader loader =
                new RedisCoordinationConfigLoader(tempDirectory);

        assertThrows(IllegalStateException.class, loader::load);
    }

    @Test
    void rejectsUnsupportedRedisScheme() throws Exception {
        Path configFile = tempDirectory.resolve(
                RedisCoordinationConfigLoader.FILE_NAME
        );
        Files.writeString(
                configFile,
                """
                redis-uri=http://127.0.0.1:6379
                membership-ttl-seconds=15
                membership-renew-seconds=5
                player-session-ttl-seconds=30
                player-session-renew-seconds=10
                """
        );

        RedisCoordinationConfigLoader loader =
                new RedisCoordinationConfigLoader(tempDirectory);

        assertThrows(IllegalStateException.class, loader::load);
    }

    private String configText(
            long membershipTtl,
            long membershipRenew,
            long playerSessionTtl,
            long playerSessionRenew
    ) {
        return """
                redis-uri=redis://127.0.0.1:6379
                membership-ttl-seconds=%d
                membership-renew-seconds=%d
                player-session-ttl-seconds=%d
                player-session-renew-seconds=%d
                """.formatted(
                membershipTtl,
                membershipRenew,
                playerSessionTtl,
                playerSessionRenew
        );
    }
}

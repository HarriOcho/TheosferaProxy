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
    }

    @Test
    void rejectsRenewIntervalNotShorterThanTtl() throws Exception {
        Path configFile = tempDirectory.resolve(
                RedisCoordinationConfigLoader.FILE_NAME
        );
        Files.writeString(
                configFile,
                """
                redis-uri=redis://127.0.0.1:6379
                membership-ttl-seconds=15
                membership-renew-seconds=15
                """
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
                """
        );

        RedisCoordinationConfigLoader loader =
                new RedisCoordinationConfigLoader(tempDirectory);

        assertThrows(IllegalStateException.class, loader::load);
    }
}

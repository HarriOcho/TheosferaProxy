package com.theosfera.proxy.coordination.distributed.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisBackendOccupancyKeyspaceTest {

    @Test
    void buildsBackendPresenceIndexKey() {
        RedisBackendOccupancyKeyspace keyspace =
                new RedisBackendOccupancyKeyspace("theosfera:test");

        assertEquals(
                "theosfera:test:backend-presence:lobby-1",
                keyspace.backendPresenceIndexKey("lobby-1")
        );
    }

    @Test
    void rejectsBlankNamespace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisBackendOccupancyKeyspace("   ")
        );
    }

    @Test
    void rejectsBlankBackendName() {
        RedisBackendOccupancyKeyspace keyspace =
                RedisBackendOccupancyKeyspace.defaultKeyspace();

        assertThrows(
                IllegalArgumentException.class,
                () -> keyspace.backendPresenceIndexKey("   ")
        );
    }
}

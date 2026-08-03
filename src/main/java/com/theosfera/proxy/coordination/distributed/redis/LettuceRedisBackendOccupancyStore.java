package com.theosfera.proxy.coordination.distributed.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

final class LettuceRedisBackendOccupancyStore
        implements RedisBackendOccupancyStore {

    private static final String COUNT_SCRIPT = """
            local occupancyKey = KEYS[1]
            local time = redis.call('TIME')
            local nowMillis = (tonumber(time[1]) * 1000)
                    + math.floor(tonumber(time[2]) / 1000)

            redis.call(
                'ZREMRANGEBYSCORE',
                occupancyKey,
                '-inf',
                nowMillis
            )

            return redis.call('ZCARD', occupancyKey)
            """;

    private final RedisScriptingAsyncCommands<String, String> commands;
    private final RedisBackendOccupancyKeyspace keyspace;

    LettuceRedisBackendOccupancyStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisBackendOccupancyKeyspace keyspace
    ) {
        this.commands = Objects.requireNonNull(
                commands,
                "commands cannot be null"
        );
        this.keyspace = Objects.requireNonNull(
                keyspace,
                "keyspace cannot be null"
        );
    }

    @Override
    public CompletionStage<Integer> countPresentPlayers(
            String backendName
    ) {
        String occupancyKey = keyspace.backendPresenceIndexKey(
                backendName
        );

        return commands.<Long>eval(
                COUNT_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{occupancyKey}
        ).thenApply(Math::toIntExact);
    }
}

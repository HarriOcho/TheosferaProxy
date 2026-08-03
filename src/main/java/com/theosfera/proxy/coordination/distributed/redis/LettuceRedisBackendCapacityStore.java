package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.transfer.BackendCapacityReservation;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

final class LettuceRedisBackendCapacityStore
        implements RedisBackendCapacityStore {

    private static final String RESERVE_SCRIPT = """
            local occupancyKey = KEYS[1]
            local reservationsKey = KEYS[2]
            local reservationKey = KEYS[3]

            local requestId = ARGV[1]
            local playerId = ARGV[2]
            local backendName = ARGV[3]
            local capacity = tonumber(ARGV[4])
            local ttlMillis = tonumber(ARGV[5])

            if capacity == nil or capacity <= 0
                    or ttlMillis == nil or ttlMillis <= 0 then
                return {'CORRUPT'}
            end

            local time = redis.call('TIME')
            local nowMillis = (tonumber(time[1]) * 1000)
                    + math.floor(tonumber(time[2]) / 1000)
            local expiresAt = nowMillis + ttlMillis

            redis.call('ZREMRANGEBYSCORE', occupancyKey, '-inf', nowMillis)
            redis.call('ZREMRANGEBYSCORE', reservationsKey, '-inf', nowMillis)

            if redis.call('EXISTS', reservationKey) == 1 then
                local keyType = redis.call('TYPE', reservationKey)
                if type(keyType) == 'table' then
                    keyType = keyType['ok']
                end
                if keyType ~= 'hash' or redis.call('PTTL', reservationKey) <= 0 then
                    return {'CORRUPT'}
                end

                local values = redis.call(
                    'HMGET', reservationKey,
                    'request-id', 'player-id', 'backend-name'
                )
                if values[1] == false or values[2] == false or values[3] == false then
                    return {'CORRUPT'}
                end
                if values[1] == requestId
                        and values[2] == playerId
                        and values[3] == backendName then
                    redis.call('PEXPIRE', reservationKey, ttlMillis)
                    redis.call('ZADD', reservationsKey, expiresAt, requestId)
                    return {'ALREADY_RESERVED'}
                end
                return {'REQUEST_ID_CONFLICT'}
            end

            local occupied = redis.call('ZCARD', occupancyKey)
            local reserved = redis.call('ZCARD', reservationsKey)
            if occupied + reserved >= capacity then
                return {'NO_CAPACITY'}
            end

            redis.call(
                'HSET', reservationKey,
                'request-id', requestId,
                'player-id', playerId,
                'backend-name', backendName
            )
            redis.call('PEXPIRE', reservationKey, ttlMillis)
            redis.call('ZADD', reservationsKey, expiresAt, requestId)
            return {'RESERVED'}
            """;

    private static final String RELEASE_SCRIPT = """
            local reservationsKey = KEYS[1]
            local reservationKey = KEYS[2]

            local requestId = ARGV[1]
            local playerId = ARGV[2]
            local backendName = ARGV[3]

            if redis.call('EXISTS', reservationKey) == 0 then
                return {'NOT_FOUND'}
            end

            local keyType = redis.call('TYPE', reservationKey)
            if type(keyType) == 'table' then
                keyType = keyType['ok']
            end
            if keyType ~= 'hash' or redis.call('PTTL', reservationKey) <= 0 then
                return {'CORRUPT'}
            end

            local values = redis.call(
                'HMGET', reservationKey,
                'request-id', 'player-id', 'backend-name'
            )
            if values[1] == false or values[2] == false or values[3] == false then
                return {'CORRUPT'}
            end
            if values[1] ~= requestId
                    or values[2] ~= playerId
                    or values[3] ~= backendName then
                return {'NOT_OWNER'}
            end

            redis.call('DEL', reservationKey)
            redis.call('ZREM', reservationsKey, requestId)
            return {'RELEASED'}
            """;

    private static final String COUNT_SCRIPT = """
            local reservationsKey = KEYS[1]
            local time = redis.call('TIME')
            local nowMillis = (tonumber(time[1]) * 1000)
                    + math.floor(tonumber(time[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', reservationsKey, '-inf', nowMillis)
            return redis.call('ZCARD', reservationsKey)
            """;

    private final RedisScriptingAsyncCommands<String, String> commands;
    private final RedisBackendOccupancyKeyspace occupancyKeyspace;
    private final RedisBackendCapacityKeyspace capacityKeyspace;

    LettuceRedisBackendCapacityStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisBackendOccupancyKeyspace occupancyKeyspace,
            RedisBackendCapacityKeyspace capacityKeyspace
    ) {
        this.commands = Objects.requireNonNull(commands, "commands cannot be null");
        this.occupancyKeyspace = Objects.requireNonNull(
                occupancyKeyspace,
                "occupancyKeyspace cannot be null"
        );
        this.capacityKeyspace = Objects.requireNonNull(
                capacityKeyspace,
                "capacityKeyspace cannot be null"
        );
    }

    @Override
    public CompletionStage<BackendCapacityReserveResult> reserve(
            BackendCapacityReservation reservation,
            int capacity,
            Duration ttl
    ) {
        BackendCapacityReservation nonNullReservation =
                Objects.requireNonNull(reservation, "reservation cannot be null");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        Duration nonNullTtl = requirePositiveTtl(ttl);

        return commands.eval(
                RESERVE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        occupancyKeyspace.backendPresenceIndexKey(
                                nonNullReservation.backendName()
                        ),
                        capacityKeyspace.backendReservationsKey(
                                nonNullReservation.backendName()
                        ),
                        capacityKeyspace.reservationKey(
                                nonNullReservation.requestId()
                        )
                },
                nonNullReservation.requestId().toString(),
                nonNullReservation.playerId().toString(),
                nonNullReservation.backendName(),
                Integer.toString(capacity),
                Long.toString(nonNullTtl.toMillis())
        ).thenApply(response -> mapReserve(nonNullReservation, requireList(response)));
    }

    @Override
    public CompletionStage<Boolean> releaseIfOwned(
            BackendCapacityReservation expected
    ) {
        BackendCapacityReservation nonNullExpected =
                Objects.requireNonNull(expected, "expected cannot be null");

        return commands.eval(
                RELEASE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        capacityKeyspace.backendReservationsKey(
                                nonNullExpected.backendName()
                        ),
                        capacityKeyspace.reservationKey(
                                nonNullExpected.requestId()
                        )
                },
                nonNullExpected.requestId().toString(),
                nonNullExpected.playerId().toString(),
                nonNullExpected.backendName()
        ).thenApply(response -> mapRelease(requireList(response)));
    }

    @Override
    public CompletionStage<Integer> reservedCount(String backendName) {
        String normalized = requireBackendName(backendName);
        return commands.eval(
                COUNT_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{capacityKeyspace.backendReservationsKey(normalized)}
        ).thenApply(value -> Math.toIntExact((Long) value));
    }

    private BackendCapacityReserveResult mapReserve(
            BackendCapacityReservation reservation,
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        String status = nextString(iterator);
        requireComplete(iterator);
        return switch (status) {
            case "RESERVED" -> BackendCapacityReserveResult.withReservation(
                    BackendCapacityReserveResult.Status.RESERVED,
                    reservation
            );
            case "ALREADY_RESERVED" -> BackendCapacityReserveResult.withReservation(
                    BackendCapacityReserveResult.Status.ALREADY_RESERVED,
                    reservation
            );
            case "REQUEST_ID_CONFLICT" -> BackendCapacityReserveResult.withoutReservation(
                    BackendCapacityReserveResult.Status.REQUEST_ID_CONFLICT
            );
            case "NO_CAPACITY" -> BackendCapacityReserveResult.withoutReservation(
                    BackendCapacityReserveResult.Status.NO_CAPACITY
            );
            case "CORRUPT" -> throw invalidState();
            default -> throw new RedisBackendCapacityInvalidStateException(
                    "Redis backend capacity reserve returned unknown status"
            );
        };
    }

    private boolean mapRelease(List<Object> response) {
        Iterator<Object> iterator = response.iterator();
        String status = nextString(iterator);
        requireComplete(iterator);
        return switch (status) {
            case "RELEASED" -> true;
            case "NOT_FOUND", "NOT_OWNER" -> false;
            case "CORRUPT" -> throw invalidState();
            default -> throw new RedisBackendCapacityInvalidStateException(
                    "Redis backend capacity release returned unknown status"
            );
        };
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(ttl, "ttl cannot be null");
        if (nonNullTtl.isZero() || nonNullTtl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return nonNullTtl;
    }

    private String requireBackendName(String backendName) {
        String normalized = Objects.requireNonNull(
                backendName,
                "backendName cannot be null"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("backendName cannot be blank");
        }
        return normalized;
    }

    private List<Object> requireList(Object response) {
        if (!(response instanceof List<?> rawResponse)) {
            throw new RedisBackendCapacityInvalidStateException(
                    "Unexpected Redis backend capacity script response"
            );
        }
        return rawResponse.stream().map(Object.class::cast).toList();
    }

    private String nextString(Iterator<Object> iterator) {
        if (!iterator.hasNext()) {
            throw new RedisBackendCapacityInvalidStateException(
                    "Redis backend capacity response is incomplete"
            );
        }
        Object value = iterator.next();
        if (value == null) {
            throw new RedisBackendCapacityInvalidStateException(
                    "Redis backend capacity response contains null"
            );
        }
        return value.toString();
    }

    private void requireComplete(Iterator<Object> iterator) {
        if (iterator.hasNext()) {
            throw new RedisBackendCapacityInvalidStateException(
                    "Redis backend capacity response has extra elements"
            );
        }
    }

    private RedisBackendCapacityInvalidStateException invalidState() {
        return new RedisBackendCapacityInvalidStateException(
                "Redis backend capacity state is corrupt"
        );
    }
}

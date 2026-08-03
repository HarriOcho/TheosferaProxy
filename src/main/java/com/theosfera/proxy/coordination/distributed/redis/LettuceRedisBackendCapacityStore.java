package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendCapacityReserveRequest;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
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
            local sessionKey = KEYS[4]

            local requestId = ARGV[1]
            local playerId = ARGV[2]
            local backendName = ARGV[3]
            local capacity = tonumber(ARGV[4])
            local ttlMillis = tonumber(ARGV[5])
            local playerName = ARGV[6]
            local authenticatedAt = ARGV[7]
            local proxyName = ARGV[8]
            local incarnationId = ARGV[9]
            local fencingToken = ARGV[10]

            if capacity == nil or capacity <= 0
                    or ttlMillis == nil or ttlMillis <= 0 then
                return {'CORRUPT'}
            end

            if redis.call('EXISTS', sessionKey) == 0 then
                return {'SESSION_NOT_FOUND'}
            end

            local sessionType = redis.call('TYPE', sessionKey)
            if type(sessionType) == 'table' then
                sessionType = sessionType['ok']
            end
            if sessionType ~= 'hash' or redis.call('PTTL', sessionKey) <= 0 then
                return {'CORRUPT'}
            end

            local sessionValues = redis.call(
                'HMGET', sessionKey,
                'player-id',
                'player-name',
                'authenticated-at',
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )
            if sessionValues[1] == false
                    or sessionValues[2] == false
                    or sessionValues[3] == false
                    or sessionValues[4] == false
                    or sessionValues[5] == false
                    or sessionValues[6] == false
                    or sessionValues[1] ~= playerId then
                return {'CORRUPT'}
            end

            if sessionValues[2] ~= playerName
                    or sessionValues[3] ~= authenticatedAt
                    or sessionValues[4] ~= proxyName
                    or sessionValues[5] ~= incarnationId
                    or sessionValues[6] ~= fencingToken then
                return {'NOT_SESSION_OWNER'}
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
                    'request-id',
                    'player-id',
                    'backend-name',
                    'proxy-name',
                    'incarnation-id',
                    'session-fencing-token'
                )
                if values[1] == false
                        or values[2] == false
                        or values[3] == false
                        or values[4] == false
                        or values[5] == false
                        or values[6] == false then
                    return {'CORRUPT'}
                end
                if values[1] == requestId
                        and values[2] == playerId
                        and values[3] == backendName
                        and values[4] == proxyName
                        and values[5] == incarnationId
                        and values[6] == fencingToken then
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
                'backend-name', backendName,
                'proxy-name', proxyName,
                'incarnation-id', incarnationId,
                'session-fencing-token', fencingToken
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
            local proxyName = ARGV[4]
            local incarnationId = ARGV[5]
            local fencingToken = ARGV[6]

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
                'request-id',
                'player-id',
                'backend-name',
                'proxy-name',
                'incarnation-id',
                'session-fencing-token'
            )
            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or values[4] == false
                    or values[5] == false
                    or values[6] == false then
                return {'CORRUPT'}
            end
            if values[1] ~= requestId
                    or values[2] ~= playerId
                    or values[3] ~= backendName
                    or values[4] ~= proxyName
                    or values[5] ~= incarnationId
                    or values[6] ~= fencingToken then
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
    private final RedisPlayerSessionKeyspace sessionKeyspace;

    LettuceRedisBackendCapacityStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisBackendOccupancyKeyspace occupancyKeyspace,
            RedisBackendCapacityKeyspace capacityKeyspace
    ) {
        this(
                commands,
                occupancyKeyspace,
                capacityKeyspace,
                RedisPlayerSessionKeyspace.defaultKeyspace()
        );
    }

    LettuceRedisBackendCapacityStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisBackendOccupancyKeyspace occupancyKeyspace,
            RedisBackendCapacityKeyspace capacityKeyspace,
            RedisPlayerSessionKeyspace sessionKeyspace
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
        this.sessionKeyspace = Objects.requireNonNull(
                sessionKeyspace,
                "sessionKeyspace cannot be null"
        );
    }

    @Override
    public CompletionStage<BackendCapacityReserveResult> reserve(
            BackendCapacityReserveRequest request,
            int capacity,
            Duration ttl
    ) {
        BackendCapacityReserveRequest nonNullRequest =
                Objects.requireNonNull(request, "request cannot be null");
        BackendCapacityReservation reservation = nonNullRequest.reservation();
        PlayerSessionLease lease = nonNullRequest.sessionLease();

        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        Duration nonNullTtl = requirePositiveTtl(ttl);

        return commands.eval(
                RESERVE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        occupancyKeyspace.backendPresenceIndexKey(
                                reservation.backendName()
                        ),
                        capacityKeyspace.backendReservationsKey(
                                reservation.backendName()
                        ),
                        capacityKeyspace.reservationKey(
                                reservation.requestId()
                        ),
                        sessionKeyspace.playerSessionKey(
                                reservation.playerId()
                        )
                },
                reservation.requestId().toString(),
                reservation.playerId().toString(),
                reservation.backendName(),
                Integer.toString(capacity),
                Long.toString(nonNullTtl.toMillis()),
                lease.session().playerName(),
                Long.toString(lease.session().authenticatedAt()),
                lease.owner().proxyName(),
                lease.owner().incarnationId().toString(),
                Long.toString(lease.fencingToken())
        ).thenApply(response -> mapReserve(reservation, requireList(response)));
    }

    @Override
    public CompletionStage<Boolean> releaseIfOwned(
            BackendCapacityReserveRequest expected
    ) {
        BackendCapacityReserveRequest nonNullExpected =
                Objects.requireNonNull(expected, "expected cannot be null");
        BackendCapacityReservation reservation = nonNullExpected.reservation();
        PlayerSessionLease lease = nonNullExpected.sessionLease();

        return commands.eval(
                RELEASE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        capacityKeyspace.backendReservationsKey(
                                reservation.backendName()
                        ),
                        capacityKeyspace.reservationKey(
                                reservation.requestId()
                        )
                },
                reservation.requestId().toString(),
                reservation.playerId().toString(),
                reservation.backendName(),
                lease.owner().proxyName(),
                lease.owner().incarnationId().toString(),
                Long.toString(lease.fencingToken())
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
            case "SESSION_NOT_FOUND" -> BackendCapacityReserveResult.withoutReservation(
                    BackendCapacityReserveResult.Status.SESSION_NOT_FOUND
            );
            case "NOT_SESSION_OWNER" -> BackendCapacityReserveResult.withoutReservation(
                    BackendCapacityReserveResult.Status.NOT_SESSION_OWNER
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
        if (nonNullTtl.isZero()
                || nonNullTtl.isNegative()
                || nonNullTtl.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "ttl must be positive and at least one millisecond"
            );
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

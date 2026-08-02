package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class LettuceRedisPlayerSessionStore
        implements RedisPlayerSessionStore {

    private static final String ACQUIRE_SCRIPT = """
            local leaseKey = KEYS[1]
            local fencingKey = KEYS[2]
            local playerId = ARGV[1]
            local playerName = ARGV[2]
            local authenticatedAt = ARGV[3]
            local proxyName = ARGV[4]
            local incarnationId = ARGV[5]
            local ttlMillis = ARGV[6]

            if redis.call('EXISTS', leaseKey) == 0 then
                local fencingToken = redis.call('INCR', fencingKey)
                redis.call(
                    'HSET',
                    leaseKey,
                    'player-id',
                    playerId,
                    'player-name',
                    playerName,
                    'authenticated-at',
                    authenticatedAt,
                    'proxy-name',
                    proxyName,
                    'incarnation-id',
                    incarnationId,
                    'fencing-token',
                    tostring(fencingToken)
                )
                redis.call('PEXPIRE', leaseKey, ttlMillis)
                return {'ACQUIRED', tostring(fencingToken)}
            end

            local keyType = redis.call('TYPE', leaseKey)
            if type(keyType) == 'table' then
                keyType = keyType['ok']
            end

            if keyType ~= 'hash' then
                return {'CORRUPT'}
            end

            local leaseTtl = redis.call('PTTL', leaseKey)
            if leaseTtl <= 0 then
                return {'CORRUPT'}
            end

            local values = redis.call(
                'HMGET',
                leaseKey,
                'player-id',
                'player-name',
                'authenticated-at',
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )

            local storedAuthenticatedAt = tonumber(values[3])
            local storedFencingToken = tonumber(values[6])

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or values[4] == false
                    or values[5] == false
                    or values[6] == false
                    or values[1] ~= playerId
                    or type(values[2]) ~= 'string'
                    or string.len(values[2]) < 3
                    or string.len(values[2]) > 16
                    or string.match(values[2], '^[A-Za-z0-9_]+$') == nil
                    or storedAuthenticatedAt == nil
                    or storedAuthenticatedAt <= 0
                    or type(values[4]) ~= 'string'
                    or string.len(values[4]) == 0
                    or type(values[5]) ~= 'string'
                    or string.len(values[5]) == 0
                    or storedFencingToken == nil
                    or storedFencingToken <= 0 then
                return {'CORRUPT'}
            end

            if values[4] ~= proxyName or values[5] ~= incarnationId then
                return {'OWNED_BY_OTHER_PROXY'}
            end

            if values[1] ~= playerId
                    or values[2] ~= playerName
                    or values[3] ~= authenticatedAt then
                return {'CONFLICT'}
            end

            return {'ALREADY_OWNED', values[6]}
            """;

    private static final String RENEW_SCRIPT = """
            local leaseKey = KEYS[1]
            local playerId = ARGV[1]
            local playerName = ARGV[2]
            local authenticatedAt = ARGV[3]
            local proxyName = ARGV[4]
            local incarnationId = ARGV[5]
            local fencingToken = ARGV[6]
            local ttlMillis = ARGV[7]

            if redis.call('EXISTS', leaseKey) == 0 then
                return {'NOT_FOUND'}
            end

            local keyType = redis.call('TYPE', leaseKey)
            if type(keyType) == 'table' then
                keyType = keyType['ok']
            end

            if keyType ~= 'hash' then
                return {'CORRUPT'}
            end

            local leaseTtl = redis.call('PTTL', leaseKey)
            if leaseTtl <= 0 then
                return {'CORRUPT'}
            end

            local values = redis.call(
                'HMGET',
                leaseKey,
                'player-id',
                'player-name',
                'authenticated-at',
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )

            local storedAuthenticatedAt = tonumber(values[3])
            local storedFencingToken = tonumber(values[6])

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or values[4] == false
                    or values[5] == false
                    or values[6] == false
                    or values[1] ~= playerId
                    or type(values[2]) ~= 'string'
                    or string.len(values[2]) < 3
                    or string.len(values[2]) > 16
                    or string.match(values[2], '^[A-Za-z0-9_]+$') == nil
                    or storedAuthenticatedAt == nil
                    or storedAuthenticatedAt <= 0
                    or type(values[4]) ~= 'string'
                    or string.len(values[4]) == 0
                    or type(values[5]) ~= 'string'
                    or string.len(values[5]) == 0
                    or storedFencingToken == nil
                    or storedFencingToken <= 0 then
                return {'CORRUPT'}
            end

            if values[4] ~= proxyName or values[5] ~= incarnationId then
                return {'NOT_OWNER'}
            end

            if values[1] ~= playerId
                    or values[2] ~= playerName
                    or values[3] ~= authenticatedAt
                    or values[6] ~= fencingToken then
                return {'CONFLICT'}
            end

            redis.call('PEXPIRE', leaseKey, ttlMillis)
            return {'RENEWED', values[6]}
            """;

    private static final String RELEASE_SCRIPT = """
            local leaseKey = KEYS[1]
            local playerId = ARGV[1]
            local playerName = ARGV[2]
            local authenticatedAt = ARGV[3]
            local proxyName = ARGV[4]
            local incarnationId = ARGV[5]
            local fencingToken = ARGV[6]

            if redis.call('EXISTS', leaseKey) == 0 then
                return 0
            end

            local keyType = redis.call('TYPE', leaseKey)
            if type(keyType) == 'table' then
                keyType = keyType['ok']
            end

            if keyType ~= 'hash' then
                return redis.error_reply(
                    'corrupt player session lease'
                )
            end

            local leaseTtl = redis.call('PTTL', leaseKey)
            if leaseTtl <= 0 then
                return redis.error_reply(
                    'corrupt player session lease'
                )
            end

            local values = redis.call(
                'HMGET',
                leaseKey,
                'player-id',
                'player-name',
                'authenticated-at',
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )

            local storedAuthenticatedAt = tonumber(values[3])
            local storedFencingToken = tonumber(values[6])

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or values[4] == false
                    or values[5] == false
                    or values[6] == false
                    or values[1] ~= playerId
                    or type(values[2]) ~= 'string'
                    or string.len(values[2]) < 3
                    or string.len(values[2]) > 16
                    or string.match(values[2], '^[A-Za-z0-9_]+$') == nil
                    or storedAuthenticatedAt == nil
                    or storedAuthenticatedAt <= 0
                    or type(values[4]) ~= 'string'
                    or string.len(values[4]) == 0
                    or type(values[5]) ~= 'string'
                    or string.len(values[5]) == 0
                    or storedFencingToken == nil
                    or storedFencingToken <= 0 then
                return redis.error_reply(
                    'corrupt player session lease'
                )
            end

            if values[1] ~= playerId
                    or values[2] ~= playerName
                    or values[3] ~= authenticatedAt
                    or values[4] ~= proxyName
                    or values[5] ~= incarnationId
                    or values[6] ~= fencingToken then
                return 0
            end

            redis.call('DEL', leaseKey)
            return 1
            """;

    private final RedisScriptingAsyncCommands<String, String>
            commands;
    private final RedisPlayerSessionKeyspace keyspace;

    public LettuceRedisPlayerSessionStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisPlayerSessionKeyspace keyspace
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
    public CompletionStage<RedisPlayerSessionAcquireResponse>
    acquire(
            PlayerSessionLeaseRequest request,
            Duration ttl
    ) {
        PlayerSessionLeaseRequest nonNullRequest =
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                );
        Duration nonNullTtl = requirePositiveTtl(ttl);

        return commands.eval(
                ACQUIRE_SCRIPT,
                ScriptOutputType.MULTI,
                keys(nonNullRequest.session().playerId()),
                acquireArguments(nonNullRequest, nonNullTtl)
        ).thenApply(
                response -> mapAcquireResponse(
                        nonNullRequest,
                        requireList(response)
                )
        );
    }

    @Override
    public CompletionStage<RedisPlayerSessionRenewResponse>
    renew(
            PlayerSessionLease expected,
            Duration ttl
    ) {
        PlayerSessionLease nonNullExpected =
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                );
        Duration nonNullTtl = requirePositiveTtl(ttl);

        return commands.eval(
                RENEW_SCRIPT,
                ScriptOutputType.MULTI,
                keys(nonNullExpected.session().playerId()),
                renewArguments(nonNullExpected, nonNullTtl)
        ).thenApply(
                response -> mapRenewResponse(
                        nonNullExpected,
                        requireList(response)
                )
        );
    }

    @Override
    public CompletionStage<Boolean> releaseIfOwned(
            PlayerSessionLease expected
    ) {
        PlayerSessionLease nonNullExpected =
                Objects.requireNonNull(
                        expected,
                        "expected cannot be null"
                );

        return commands.eval(
                RELEASE_SCRIPT,
                ScriptOutputType.INTEGER,
                keys(nonNullExpected.session().playerId()),
                releaseArguments(nonNullExpected)
        ).thenApply(
                response -> {
                    if (!(response instanceof Long released)) {
                        throw new RedisPlayerSessionInvalidStateException(
                                "Unexpected Redis release response"
                        );
                    }

                    return released == 1L;
                }
        );
    }

    private RedisPlayerSessionAcquireResponse mapAcquireResponse(
            PlayerSessionLeaseRequest request,
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();

        String rawStatus = nextString(iterator);

        RedisPlayerSessionAcquireStatus status =
                acquireStatus(rawStatus);

        return switch (status) {
            case ACQUIRED ->
                    RedisPlayerSessionAcquireResponse.acquired(
                            requireComplete(
                                    iterator,
                                    lease(
                                            request,
                                            nextPositiveLong(iterator)
                                    )
                            )
                    );
            case ALREADY_OWNED ->
                    RedisPlayerSessionAcquireResponse.alreadyOwned(
                            requireComplete(
                                    iterator,
                                    lease(
                                            request,
                                            nextPositiveLong(iterator)
                                    )
                            )
                    );
            case OWNED_BY_OTHER_PROXY, CONFLICT, CORRUPT ->
                    requireComplete(
                            iterator,
                            RedisPlayerSessionAcquireResponse
                                    .withoutLease(status)
                    );
        };
    }

    private RedisPlayerSessionRenewResponse mapRenewResponse(
            PlayerSessionLease expected,
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();

        String rawStatus = nextString(iterator);

        RedisPlayerSessionRenewStatus status =
                renewStatus(rawStatus);

        return switch (status) {
            case RENEWED -> {
                long token = nextPositiveLong(iterator);

                if (token != expected.fencingToken()) {
                    throw new RedisPlayerSessionInvalidStateException(
                            "Redis renew returned a different "
                                    + "fencing token"
                    );
                }

                yield requireComplete(
                        iterator,
                        RedisPlayerSessionRenewResponse.renewed(
                                expected
                        )
                );
            }
            case NOT_FOUND, NOT_OWNER, CONFLICT, CORRUPT ->
                    requireComplete(
                            iterator,
                            RedisPlayerSessionRenewResponse
                                    .withoutLease(status)
                    );
        };
    }

    private RedisPlayerSessionAcquireStatus acquireStatus(
            String rawStatus
    ) {
        try {
            return RedisPlayerSessionAcquireStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new RedisPlayerSessionInvalidStateException(
                    "Redis acquire returned an unknown status",
                    exception
            );
        }
    }

    private RedisPlayerSessionRenewStatus renewStatus(
            String rawStatus
    ) {
        try {
            return RedisPlayerSessionRenewStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new RedisPlayerSessionInvalidStateException(
                    "Redis renew returned an unknown status",
                    exception
            );
        }
    }

    private <T> T requireComplete(
            Iterator<Object> iterator,
            T value
    ) {
        if (iterator.hasNext()) {
            throw new RedisPlayerSessionInvalidStateException(
                    "Redis script response contains unexpected elements"
            );
        }

        return value;
    }

    private List<Object> requireList(Object response) {
        if (!(response instanceof List<?> rawResponse)) {
            throw new RedisPlayerSessionInvalidStateException(
                    "Unexpected Redis script response"
            );
        }

        return rawResponse.stream()
                .map(Object.class::cast)
                .toList();
    }

    private String nextString(Iterator<Object> iterator) {
        if (!iterator.hasNext()) {
            throw new RedisPlayerSessionInvalidStateException(
                    "Redis script response is incomplete"
            );
        }

        Object value = iterator.next();

        if (value == null) {
            throw new RedisPlayerSessionInvalidStateException(
                    "Redis script response contains null"
            );
        }

        return value.toString();
    }

    private long nextPositiveLong(Iterator<Object> iterator) {
        String value = nextString(iterator);

        try {
            long parsed = Long.parseLong(value);

            if (parsed <= 0) {
                throw new RedisPlayerSessionInvalidStateException(
                        "Redis fencing token must be positive"
                );
            }

            return parsed;
        } catch (NumberFormatException exception) {
            throw new RedisPlayerSessionInvalidStateException(
                    "Redis fencing token is not numeric",
                    exception
            );
        }
    }

    private PlayerSessionLease lease(
            PlayerSessionLeaseRequest request,
            long fencingToken
    ) {
        return new PlayerSessionLease(
                request.session(),
                request.owner(),
                fencingToken
        );
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(
                ttl,
                "ttl cannot be null"
        );

        if (nonNullTtl.isZero()
                || nonNullTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "ttl must be positive"
            );
        }

        return nonNullTtl;
    }

    private String[] keys(UUID playerId) {
        return new String[]{
                keyspace.playerSessionKey(playerId),
                keyspace.fencingCounterKey()
        };
    }

    private String[] acquireArguments(
            PlayerSessionLeaseRequest request,
            Duration ttl
    ) {
        return new String[]{
                request.session().playerId().toString(),
                request.session().playerName(),
                Long.toString(request.session().authenticatedAt()),
                request.owner().proxyName(),
                request.owner().incarnationId().toString(),
                Long.toString(ttl.toMillis())
        };
    }

    private String[] renewArguments(
            PlayerSessionLease expected,
            Duration ttl
    ) {
        return new String[]{
                expected.session().playerId().toString(),
                expected.session().playerName(),
                Long.toString(expected.session().authenticatedAt()),
                expected.owner().proxyName(),
                expected.owner().incarnationId().toString(),
                Long.toString(expected.fencingToken()),
                Long.toString(ttl.toMillis())
        };
    }

    private String[] releaseArguments(
            PlayerSessionLease expected
    ) {
        return new String[]{
                expected.session().playerId().toString(),
                expected.session().playerName(),
                Long.toString(expected.session().authenticatedAt()),
                expected.owner().proxyName(),
                expected.owner().incarnationId().toString(),
                Long.toString(expected.fencingToken())
        };
    }
}

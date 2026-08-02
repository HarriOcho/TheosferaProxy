package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class LettuceRedisProxyMembershipStore
        implements RedisProxyMembershipStore {

    private static final String ACQUIRE_SCRIPT = """
            local leaseKey = KEYS[1]
            local fencingKey = KEYS[2]
            local proxyName = ARGV[1]
            local incarnationId = ARGV[2]
            local ttlMillis = ARGV[3]

            if redis.call('EXISTS', leaseKey) == 0 then
                local fencingToken = redis.call('INCR', fencingKey)
                redis.call(
                    'HSET',
                    leaseKey,
                    'proxy-name', proxyName,
                    'incarnation-id', incarnationId,
                    'fencing-token', tostring(fencingToken)
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
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )
            local storedFencingToken = tonumber(values[3])

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or type(values[1]) ~= 'string'
                    or string.len(values[1]) == 0
                    or type(values[2]) ~= 'string'
                    or string.len(values[2]) == 0
                    or storedFencingToken == nil
                    or storedFencingToken <= 0 then
                return {'CORRUPT'}
            end

            if values[1] ~= proxyName then
                return {'CORRUPT'}
            end

            if values[2] ~= incarnationId then
                return {'OWNED_BY_OTHER_INCARNATION'}
            end

            return {'ALREADY_OWNED', values[3]}
            """;

    private static final String RENEW_SCRIPT = """
            local leaseKey = KEYS[1]
            local proxyName = ARGV[1]
            local incarnationId = ARGV[2]
            local fencingToken = ARGV[3]
            local ttlMillis = ARGV[4]

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
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )
            local storedFencingToken = tonumber(values[3])

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or type(values[1]) ~= 'string'
                    or string.len(values[1]) == 0
                    or type(values[2]) ~= 'string'
                    or string.len(values[2]) == 0
                    or storedFencingToken == nil
                    or storedFencingToken <= 0 then
                return {'CORRUPT'}
            end

            if values[1] ~= proxyName then
                return {'CORRUPT'}
            end

            if values[2] ~= incarnationId then
                return {'NOT_OWNER'}
            end

            if values[3] ~= fencingToken then
                return {'CONFLICT'}
            end

            redis.call('PEXPIRE', leaseKey, ttlMillis)
            return {'RENEWED', values[3]}
            """;

    private static final String RELEASE_SCRIPT = """
            local leaseKey = KEYS[1]
            local proxyName = ARGV[1]
            local incarnationId = ARGV[2]
            local fencingToken = ARGV[3]

            if redis.call('EXISTS', leaseKey) == 0 then
                return 0
            end

            local keyType = redis.call('TYPE', leaseKey)
            if type(keyType) == 'table' then
                keyType = keyType['ok']
            end
            if keyType ~= 'hash' then
                return redis.error_reply('corrupt proxy membership lease')
            end

            local leaseTtl = redis.call('PTTL', leaseKey)
            if leaseTtl <= 0 then
                return redis.error_reply('corrupt proxy membership lease')
            end

            local values = redis.call(
                'HMGET',
                leaseKey,
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )
            local storedFencingToken = tonumber(values[3])

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or type(values[1]) ~= 'string'
                    or string.len(values[1]) == 0
                    or type(values[2]) ~= 'string'
                    or string.len(values[2]) == 0
                    or storedFencingToken == nil
                    or storedFencingToken <= 0 then
                return redis.error_reply('corrupt proxy membership lease')
            end

            if values[1] ~= proxyName then
                return redis.error_reply('corrupt proxy membership lease')
            end

            if values[2] ~= incarnationId or values[3] ~= fencingToken then
                return 0
            end

            redis.call('DEL', leaseKey)
            return 1
            """;

    private final RedisScriptingAsyncCommands<String, String> commands;
    private final RedisProxyMembershipKeyspace keyspace;

    public LettuceRedisProxyMembershipStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisProxyMembershipKeyspace keyspace
    ) {
        this.commands = Objects.requireNonNull(commands, "commands cannot be null");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace cannot be null");
    }

    @Override
    public CompletionStage<RedisProxyMembershipAcquireResponse> acquire(
            ProxyInstanceIdentity identity,
            Duration ttl
    ) {
        ProxyInstanceIdentity nonNullIdentity = Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );
        Duration nonNullTtl = requirePositiveTtl(ttl);

        return commands.eval(
                ACQUIRE_SCRIPT,
                ScriptOutputType.MULTI,
                keys(nonNullIdentity.proxyName()),
                nonNullIdentity.proxyName(),
                nonNullIdentity.incarnationId().toString(),
                Long.toString(nonNullTtl.toMillis())
        ).thenApply(
                response -> mapAcquireResponse(
                        nonNullIdentity,
                        requireList(response)
                )
        );
    }

    @Override
    public CompletionStage<RedisProxyMembershipRenewResponse> renew(
            ProxyMembershipLease expected,
            Duration ttl
    ) {
        ProxyMembershipLease nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );
        Duration nonNullTtl = requirePositiveTtl(ttl);

        return commands.eval(
                RENEW_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{keyspace.membershipKey(nonNullExpected.owner().proxyName())},
                nonNullExpected.owner().proxyName(),
                nonNullExpected.owner().incarnationId().toString(),
                Long.toString(nonNullExpected.fencingToken()),
                Long.toString(nonNullTtl.toMillis())
        ).thenApply(
                response -> mapRenewResponse(
                        nonNullExpected,
                        requireList(response)
                )
        );
    }

    @Override
    public CompletionStage<Boolean> releaseIfOwned(
            ProxyMembershipLease expected
    ) {
        ProxyMembershipLease nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );

        return commands.eval(
                RELEASE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{keyspace.membershipKey(nonNullExpected.owner().proxyName())},
                nonNullExpected.owner().proxyName(),
                nonNullExpected.owner().incarnationId().toString(),
                Long.toString(nonNullExpected.fencingToken())
        ).thenApply(
                response -> {
                    if (!(response instanceof Long released)) {
                        throw new RedisProxyMembershipInvalidStateException(
                                "Unexpected Redis release response"
                        );
                    }
                    return released == 1L;
                }
        );
    }

    private RedisProxyMembershipAcquireResponse mapAcquireResponse(
            ProxyInstanceIdentity identity,
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        RedisProxyMembershipAcquireStatus status = acquireStatus(nextString(iterator));

        return switch (status) {
            case ACQUIRED, ALREADY_OWNED -> {
                long token = nextPositiveLong(iterator);
                ProxyMembershipLease lease = new ProxyMembershipLease(identity, token);
                yield requireComplete(
                        iterator,
                        RedisProxyMembershipAcquireResponse.withLease(status, lease)
                );
            }
            case OWNED_BY_OTHER_INCARNATION, CORRUPT ->
                    requireComplete(
                            iterator,
                            RedisProxyMembershipAcquireResponse.withoutLease(status)
                    );
        };
    }

    private RedisProxyMembershipRenewResponse mapRenewResponse(
            ProxyMembershipLease expected,
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        RedisProxyMembershipRenewStatus status = renewStatus(nextString(iterator));

        return switch (status) {
            case RENEWED -> {
                long token = nextPositiveLong(iterator);
                if (token != expected.fencingToken()) {
                    throw new RedisProxyMembershipInvalidStateException(
                            "Redis renew returned a different fencing token"
                    );
                }
                yield requireComplete(
                        iterator,
                        RedisProxyMembershipRenewResponse.renewed(expected)
                );
            }
            case NOT_FOUND, NOT_OWNER, CONFLICT, CORRUPT ->
                    requireComplete(
                            iterator,
                            RedisProxyMembershipRenewResponse.withoutLease(status)
                    );
        };
    }

    private RedisProxyMembershipAcquireStatus acquireStatus(String rawStatus) {
        try {
            return RedisProxyMembershipAcquireStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new RedisProxyMembershipInvalidStateException(
                    "Redis acquire returned an unknown status",
                    exception
            );
        }
    }

    private RedisProxyMembershipRenewStatus renewStatus(String rawStatus) {
        try {
            return RedisProxyMembershipRenewStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new RedisProxyMembershipInvalidStateException(
                    "Redis renew returned an unknown status",
                    exception
            );
        }
    }

    private <T> T requireComplete(Iterator<Object> iterator, T value) {
        if (iterator.hasNext()) {
            throw new RedisProxyMembershipInvalidStateException(
                    "Redis script response contains unexpected elements"
            );
        }
        return value;
    }

    private List<Object> requireList(Object response) {
        if (!(response instanceof List<?> rawResponse)) {
            throw new RedisProxyMembershipInvalidStateException(
                    "Unexpected Redis script response"
            );
        }
        return rawResponse.stream().map(Object.class::cast).toList();
    }

    private String nextString(Iterator<Object> iterator) {
        if (!iterator.hasNext()) {
            throw new RedisProxyMembershipInvalidStateException(
                    "Redis script response is incomplete"
            );
        }
        Object value = iterator.next();
        if (value == null) {
            throw new RedisProxyMembershipInvalidStateException(
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
                throw new RedisProxyMembershipInvalidStateException(
                        "Redis fencing token must be positive"
                );
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new RedisProxyMembershipInvalidStateException(
                    "Redis fencing token is not numeric",
                    exception
            );
        }
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(ttl, "ttl cannot be null");
        if (nonNullTtl.isZero() || nonNullTtl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (nonNullTtl.toMillis() <= 0) {
            throw new IllegalArgumentException("ttl must be at least one millisecond");
        }
        return nonNullTtl;
    }

    private String[] keys(String proxyName) {
        return new String[]{
                keyspace.membershipKey(proxyName),
                keyspace.fencingCounterKey()
        };
    }
}

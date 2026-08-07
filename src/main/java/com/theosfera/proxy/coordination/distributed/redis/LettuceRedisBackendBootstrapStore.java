package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.BackendBootstrapAcquireRequest;
import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class LettuceRedisBackendBootstrapStore
        implements RedisBackendBootstrapStore {

    private static final String ACQUIRE_SCRIPT = """
            local membershipKey = KEYS[1]
            local leaseKey = KEYS[2]
            local requestKey = KEYS[3]
            local fencingKey = KEYS[4]

            local proxyName = ARGV[1]
            local incarnationId = ARGV[2]
            local membershipFencingToken = ARGV[3]
            local backendName = ARGV[4]
            local requestId = ARGV[5]
            local playerId = ARGV[6]
            local ttlMillis = ARGV[7]

            if redis.call('EXISTS', membershipKey) == 0 then
                return {'MEMBERSHIP_NOT_FOUND'}
            end

            local membershipType = redis.call('TYPE', membershipKey)
            if type(membershipType) == 'table' then
                membershipType = membershipType['ok']
            end
            if membershipType ~= 'hash' then
                return {'CORRUPT'}
            end

            local membershipTtl = redis.call('PTTL', membershipKey)
            if membershipTtl <= 0 then
                return {'CORRUPT'}
            end

            local membershipValues = redis.call(
                'HMGET',
                membershipKey,
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )
            local storedMembershipFencing = tonumber(membershipValues[3])

            if membershipValues[1] == false
                    or membershipValues[2] == false
                    or membershipValues[3] == false
                    or type(membershipValues[1]) ~= 'string'
                    or string.len(membershipValues[1]) == 0
                    or type(membershipValues[2]) ~= 'string'
                    or string.len(membershipValues[2]) == 0
                    or storedMembershipFencing == nil
                    or storedMembershipFencing <= 0 then
                return {'CORRUPT'}
            end

            if membershipValues[1] ~= proxyName then
                return {'CORRUPT'}
            end

            if membershipValues[2] ~= incarnationId
                    or membershipValues[3] ~= membershipFencingToken then
                return {'NOT_MEMBERSHIP_OWNER'}
            end

            if redis.call('EXISTS', requestKey) ~= 0 then
                local requestType = redis.call('TYPE', requestKey)
                if type(requestType) == 'table' then
                    requestType = requestType['ok']
                end
                if requestType ~= 'string' then
                    return {'CORRUPT'}
                end

                local requestTtl = redis.call('PTTL', requestKey)
                if requestTtl <= 0 then
                    return {'CORRUPT'}
                end

                local indexedBackend = redis.call('GET', requestKey)
                if indexedBackend ~= backendName then
                    return {'REQUEST_ID_CONFLICT'}
                end

                if redis.call('EXISTS', leaseKey) == 0 then
                    return {'CORRUPT'}
                end
            end

            if redis.call('EXISTS', leaseKey) ~= 0 then
                local leaseType = redis.call('TYPE', leaseKey)
                if type(leaseType) == 'table' then
                    leaseType = leaseType['ok']
                end
                if leaseType ~= 'hash' then
                    return {'CORRUPT'}
                end

                local leaseTtl = redis.call('PTTL', leaseKey)
                if leaseTtl <= 0 then
                    return {'CORRUPT'}
                end

                local leaseValues = redis.call(
                    'HMGET',
                    leaseKey,
                    'backend-name',
                    'request-id',
                    'player-id',
                    'proxy-name',
                    'incarnation-id',
                    'membership-fencing-token',
                    'bootstrap-fencing-token'
                )
                local storedBootstrapFencing = tonumber(leaseValues[7])
                local storedLeaseMembershipFencing = tonumber(leaseValues[6])

                if leaseValues[1] == false
                        or leaseValues[2] == false
                        or leaseValues[3] == false
                        or leaseValues[4] == false
                        or leaseValues[5] == false
                        or leaseValues[6] == false
                        or leaseValues[7] == false
                        or type(leaseValues[1]) ~= 'string'
                        or string.len(leaseValues[1]) == 0
                        or type(leaseValues[2]) ~= 'string'
                        or string.len(leaseValues[2]) == 0
                        or type(leaseValues[3]) ~= 'string'
                        or string.len(leaseValues[3]) == 0
                        or type(leaseValues[4]) ~= 'string'
                        or string.len(leaseValues[4]) == 0
                        or type(leaseValues[5]) ~= 'string'
                        or string.len(leaseValues[5]) == 0
                        or storedLeaseMembershipFencing == nil
                        or storedLeaseMembershipFencing <= 0
                        or storedBootstrapFencing == nil
                        or storedBootstrapFencing <= 0 then
                    return {'CORRUPT'}
                end

                if leaseValues[1] ~= backendName then
                    return {'CORRUPT'}
                end

                if leaseValues[2] == requestId then
                    if leaseValues[3] ~= playerId
                            or leaseValues[4] ~= proxyName
                            or leaseValues[5] ~= incarnationId
                            or leaseValues[6] ~= membershipFencingToken then
                        return {'REQUEST_ID_CONFLICT'}
                    end

                    if redis.call('EXISTS', requestKey) == 0 then
                        return {'CORRUPT'}
                    end

                    return {'ALREADY_OWNED', leaseValues[7]}
                end

                return {'TARGET_BUSY'}
            end

            if redis.call('EXISTS', requestKey) ~= 0 then
                return {'CORRUPT'}
            end

            local bootstrapFencingToken = redis.call('INCR', fencingKey)

            redis.call(
                'HSET',
                leaseKey,
                'backend-name', backendName,
                'request-id', requestId,
                'player-id', playerId,
                'proxy-name', proxyName,
                'incarnation-id', incarnationId,
                'membership-fencing-token', membershipFencingToken,
                'bootstrap-fencing-token', tostring(bootstrapFencingToken)
            )
            redis.call('PEXPIRE', leaseKey, ttlMillis)
            redis.call('SET', requestKey, backendName, 'PX', ttlMillis)

            return {'ACQUIRED', tostring(bootstrapFencingToken)}
            """;

    private static final String RENEW_SCRIPT = """
            local membershipKey = KEYS[1]
            local leaseKey = KEYS[2]
            local requestKey = KEYS[3]

            local proxyName = ARGV[1]
            local incarnationId = ARGV[2]
            local membershipFencingToken = ARGV[3]
            local backendName = ARGV[4]
            local requestId = ARGV[5]
            local playerId = ARGV[6]
            local bootstrapFencingToken = ARGV[7]
            local ttlMillis = ARGV[8]

            if redis.call('EXISTS', membershipKey) == 0 then
                return {'MEMBERSHIP_NOT_FOUND'}
            end

            local membershipType = redis.call('TYPE', membershipKey)
            if type(membershipType) == 'table' then
                membershipType = membershipType['ok']
            end
            if membershipType ~= 'hash' then
                return {'CORRUPT'}
            end

            local membershipTtl = redis.call('PTTL', membershipKey)
            if membershipTtl <= 0 then
                return {'CORRUPT'}
            end

            local membershipValues = redis.call(
                'HMGET',
                membershipKey,
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )
            local storedMembershipFencing = tonumber(membershipValues[3])

            if membershipValues[1] == false
                    or membershipValues[2] == false
                    or membershipValues[3] == false
                    or storedMembershipFencing == nil
                    or storedMembershipFencing <= 0 then
                return {'CORRUPT'}
            end

            if membershipValues[1] ~= proxyName then
                return {'CORRUPT'}
            end

            if membershipValues[2] ~= incarnationId
                    or membershipValues[3] ~= membershipFencingToken then
                return {'NOT_MEMBERSHIP_OWNER'}
            end

            if redis.call('EXISTS', leaseKey) == 0 then
                if redis.call('EXISTS', requestKey) ~= 0 then
                    return {'CORRUPT'}
                end
                return {'NOT_FOUND'}
            end

            local leaseType = redis.call('TYPE', leaseKey)
            if type(leaseType) == 'table' then
                leaseType = leaseType['ok']
            end
            if leaseType ~= 'hash' then
                return {'CORRUPT'}
            end

            local leaseTtl = redis.call('PTTL', leaseKey)
            if leaseTtl <= 0 then
                return {'CORRUPT'}
            end

            local leaseValues = redis.call(
                'HMGET',
                leaseKey,
                'backend-name',
                'request-id',
                'player-id',
                'proxy-name',
                'incarnation-id',
                'membership-fencing-token',
                'bootstrap-fencing-token'
            )
            local storedLeaseMembershipFencing = tonumber(leaseValues[6])
            local storedBootstrapFencing = tonumber(leaseValues[7])

            if leaseValues[1] == false
                    or leaseValues[2] == false
                    or leaseValues[3] == false
                    or leaseValues[4] == false
                    or leaseValues[5] == false
                    or leaseValues[6] == false
                    or leaseValues[7] == false
                    or storedLeaseMembershipFencing == nil
                    or storedLeaseMembershipFencing <= 0
                    or storedBootstrapFencing == nil
                    or storedBootstrapFencing <= 0 then
                return {'CORRUPT'}
            end

            if leaseValues[1] ~= backendName then
                return {'CORRUPT'}
            end

            if leaseValues[4] ~= proxyName
                    or leaseValues[5] ~= incarnationId then
                return {'NOT_OWNER'}
            end

            if leaseValues[2] ~= requestId
                    or leaseValues[3] ~= playerId
                    or leaseValues[6] ~= membershipFencingToken
                    or leaseValues[7] ~= bootstrapFencingToken then
                return {'CONFLICT'}
            end

            if redis.call('EXISTS', requestKey) == 0 then
                return {'CORRUPT'}
            end

            local requestType = redis.call('TYPE', requestKey)
            if type(requestType) == 'table' then
                requestType = requestType['ok']
            end
            if requestType ~= 'string' then
                return {'CORRUPT'}
            end

            local requestTtl = redis.call('PTTL', requestKey)
            if requestTtl <= 0 then
                return {'CORRUPT'}
            end

            if redis.call('GET', requestKey) ~= backendName then
                return {'CORRUPT'}
            end

            redis.call('PEXPIRE', leaseKey, ttlMillis)
            redis.call('PEXPIRE', requestKey, ttlMillis)

            return {'RENEWED', leaseValues[7]}
            """;

    private static final String RELEASE_SCRIPT = """
            local membershipKey = KEYS[1]
            local leaseKey = KEYS[2]
            local requestKey = KEYS[3]

            local proxyName = ARGV[1]
            local incarnationId = ARGV[2]
            local membershipFencingToken = ARGV[3]
            local backendName = ARGV[4]
            local requestId = ARGV[5]
            local playerId = ARGV[6]
            local bootstrapFencingToken = ARGV[7]

            if redis.call('EXISTS', membershipKey) == 0 then
                return {'MEMBERSHIP_NOT_FOUND'}
            end

            local membershipType = redis.call('TYPE', membershipKey)
            if type(membershipType) == 'table' then
                membershipType = membershipType['ok']
            end
            if membershipType ~= 'hash' then
                return {'CORRUPT'}
            end

            local membershipTtl = redis.call('PTTL', membershipKey)
            if membershipTtl <= 0 then
                return {'CORRUPT'}
            end

            local membershipValues = redis.call(
                'HMGET',
                membershipKey,
                'proxy-name',
                'incarnation-id',
                'fencing-token'
            )
            local storedMembershipFencing = tonumber(membershipValues[3])

            if membershipValues[1] == false
                    or membershipValues[2] == false
                    or membershipValues[3] == false
                    or storedMembershipFencing == nil
                    or storedMembershipFencing <= 0 then
                return {'CORRUPT'}
            end

            if membershipValues[1] ~= proxyName then
                return {'CORRUPT'}
            end

            if membershipValues[2] ~= incarnationId
                    or membershipValues[3] ~= membershipFencingToken then
                return {'NOT_MEMBERSHIP_OWNER'}
            end

            if redis.call('EXISTS', leaseKey) == 0 then
                if redis.call('EXISTS', requestKey) ~= 0 then
                    return {'CORRUPT'}
                end
                return {'NOT_FOUND'}
            end

            local leaseType = redis.call('TYPE', leaseKey)
            if type(leaseType) == 'table' then
                leaseType = leaseType['ok']
            end
            if leaseType ~= 'hash' then
                return {'CORRUPT'}
            end

            local leaseTtl = redis.call('PTTL', leaseKey)
            if leaseTtl <= 0 then
                return {'CORRUPT'}
            end

            local leaseValues = redis.call(
                'HMGET',
                leaseKey,
                'backend-name',
                'request-id',
                'player-id',
                'proxy-name',
                'incarnation-id',
                'membership-fencing-token',
                'bootstrap-fencing-token'
            )
            local storedLeaseMembershipFencing = tonumber(leaseValues[6])
            local storedBootstrapFencing = tonumber(leaseValues[7])

            if leaseValues[1] == false
                    or leaseValues[2] == false
                    or leaseValues[3] == false
                    or leaseValues[4] == false
                    or leaseValues[5] == false
                    or leaseValues[6] == false
                    or leaseValues[7] == false
                    or storedLeaseMembershipFencing == nil
                    or storedLeaseMembershipFencing <= 0
                    or storedBootstrapFencing == nil
                    or storedBootstrapFencing <= 0 then
                return {'CORRUPT'}
            end

            if leaseValues[1] ~= backendName then
                return {'CORRUPT'}
            end

            if leaseValues[4] ~= proxyName
                    or leaseValues[5] ~= incarnationId then
                return {'NOT_OWNER'}
            end

            if leaseValues[2] ~= requestId
                    or leaseValues[3] ~= playerId
                    or leaseValues[6] ~= membershipFencingToken
                    or leaseValues[7] ~= bootstrapFencingToken then
                return {'CONFLICT'}
            end

            if redis.call('EXISTS', requestKey) == 0 then
                return {'CORRUPT'}
            end

            local requestType = redis.call('TYPE', requestKey)
            if type(requestType) == 'table' then
                requestType = requestType['ok']
            end
            if requestType ~= 'string' then
                return {'CORRUPT'}
            end

            local requestTtl = redis.call('PTTL', requestKey)
            if requestTtl <= 0 then
                return {'CORRUPT'}
            end

            if redis.call('GET', requestKey) ~= backendName then
                return {'CORRUPT'}
            end

            redis.call('DEL', leaseKey)
            redis.call('DEL', requestKey)
            return {'RELEASED'}
            """;

    private final RedisScriptingAsyncCommands<String, String> commands;
    private final RedisBackendBootstrapKeyspace bootstrapKeyspace;
    private final RedisProxyMembershipKeyspace membershipKeyspace;

    public LettuceRedisBackendBootstrapStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisBackendBootstrapKeyspace bootstrapKeyspace,
            RedisProxyMembershipKeyspace membershipKeyspace
    ) {
        this.commands = Objects.requireNonNull(
                commands,
                "commands cannot be null"
        );
        this.bootstrapKeyspace = Objects.requireNonNull(
                bootstrapKeyspace,
                "bootstrapKeyspace cannot be null"
        );
        this.membershipKeyspace = Objects.requireNonNull(
                membershipKeyspace,
                "membershipKeyspace cannot be null"
        );
    }

    @Override
    public CompletionStage<RedisBackendBootstrapAcquireResponse> acquire(
            BackendBootstrapAcquireRequest request,
            Duration ttl
    ) {
        BackendBootstrapAcquireRequest nonNullRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        Duration nonNullTtl = requirePositiveTtl(ttl);
        ProxyMembershipLease membershipLease = nonNullRequest.membershipLease();

        return commands.eval(
                ACQUIRE_SCRIPT,
                ScriptOutputType.MULTI,
                acquireKeys(nonNullRequest),
                membershipLease.owner().proxyName(),
                membershipLease.owner().incarnationId().toString(),
                Long.toString(membershipLease.fencingToken()),
                nonNullRequest.targetBackendName(),
                nonNullRequest.requestId().toString(),
                nonNullRequest.playerId().toString(),
                Long.toString(nonNullTtl.toMillis())
        ).thenApply(
                response -> mapAcquireResponse(
                        nonNullRequest,
                        requireList(response)
                )
        );
    }

    @Override
    public CompletionStage<RedisBackendBootstrapRenewResponse> renew(
            BackendBootstrapLease expected,
            Duration ttl
    ) {
        BackendBootstrapLease nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );
        Duration nonNullTtl = requirePositiveTtl(ttl);
        ProxyMembershipLease membershipLease =
                nonNullExpected.ownerMembership();

        return commands.eval(
                RENEW_SCRIPT,
                ScriptOutputType.MULTI,
                leaseKeys(nonNullExpected),
                membershipLease.owner().proxyName(),
                membershipLease.owner().incarnationId().toString(),
                Long.toString(membershipLease.fencingToken()),
                nonNullExpected.targetBackendName(),
                nonNullExpected.requestId().toString(),
                nonNullExpected.playerId().toString(),
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
    public CompletionStage<RedisBackendBootstrapReleaseResponse> releaseIfOwned(
            BackendBootstrapLease expected
    ) {
        BackendBootstrapLease nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );
        ProxyMembershipLease membershipLease =
                nonNullExpected.ownerMembership();

        return commands.eval(
                RELEASE_SCRIPT,
                ScriptOutputType.MULTI,
                leaseKeys(nonNullExpected),
                membershipLease.owner().proxyName(),
                membershipLease.owner().incarnationId().toString(),
                Long.toString(membershipLease.fencingToken()),
                nonNullExpected.targetBackendName(),
                nonNullExpected.requestId().toString(),
                nonNullExpected.playerId().toString(),
                Long.toString(nonNullExpected.fencingToken())
        ).thenApply(
                response -> mapReleaseResponse(
                        requireList(response)
                )
        );
    }

    private RedisBackendBootstrapAcquireResponse mapAcquireResponse(
            BackendBootstrapAcquireRequest request,
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        RedisBackendBootstrapAcquireStatus status =
                acquireStatus(nextString(iterator));

        return switch (status) {
            case ACQUIRED, ALREADY_OWNED -> {
                long token = nextPositiveLong(iterator);
                BackendBootstrapLease lease = new BackendBootstrapLease(
                        request.targetBackendName(),
                        request.requestId(),
                        request.playerId(),
                        request.membershipLease(),
                        token
                );
                yield requireComplete(
                        iterator,
                        RedisBackendBootstrapAcquireResponse.withLease(
                                status,
                                lease
                        )
                );
            }
            case TARGET_BUSY,
                    REQUEST_ID_CONFLICT,
                    MEMBERSHIP_NOT_FOUND,
                    NOT_MEMBERSHIP_OWNER,
                    CORRUPT -> requireComplete(
                    iterator,
                    RedisBackendBootstrapAcquireResponse.withoutLease(status)
            );
        };
    }

    private RedisBackendBootstrapRenewResponse mapRenewResponse(
            BackendBootstrapLease expected,
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        RedisBackendBootstrapRenewStatus status =
                renewStatus(nextString(iterator));

        return switch (status) {
            case RENEWED -> {
                long token = nextPositiveLong(iterator);
                if (token != expected.fencingToken()) {
                    throw new RedisBackendBootstrapInvalidStateException(
                            "Redis bootstrap renew returned a different fencing token"
                    );
                }
                yield requireComplete(
                        iterator,
                        RedisBackendBootstrapRenewResponse.renewed(expected)
                );
            }
            case NOT_FOUND,
                    NOT_OWNER,
                    CONFLICT,
                    MEMBERSHIP_NOT_FOUND,
                    NOT_MEMBERSHIP_OWNER,
                    CORRUPT -> requireComplete(
                    iterator,
                    RedisBackendBootstrapRenewResponse.withoutLease(status)
            );
        };
    }

    private RedisBackendBootstrapReleaseResponse mapReleaseResponse(
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        RedisBackendBootstrapReleaseStatus status =
                releaseStatus(nextString(iterator));

        return requireComplete(
                iterator,
                new RedisBackendBootstrapReleaseResponse(status)
        );
    }

    private RedisBackendBootstrapAcquireStatus acquireStatus(String rawStatus) {
        try {
            return RedisBackendBootstrapAcquireStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new RedisBackendBootstrapInvalidStateException(
                    "Redis bootstrap acquire returned an unknown status",
                    exception
            );
        }
    }

    private RedisBackendBootstrapRenewStatus renewStatus(String rawStatus) {
        try {
            return RedisBackendBootstrapRenewStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new RedisBackendBootstrapInvalidStateException(
                    "Redis bootstrap renew returned an unknown status",
                    exception
            );
        }
    }

    private RedisBackendBootstrapReleaseStatus releaseStatus(
            String rawStatus
    ) {
        try {
            return RedisBackendBootstrapReleaseStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException exception) {
            throw new RedisBackendBootstrapInvalidStateException(
                    "Redis bootstrap release returned an unknown status",
                    exception
            );
        }
    }

    private <T> T requireComplete(Iterator<Object> iterator, T value) {
        if (iterator.hasNext()) {
            throw new RedisBackendBootstrapInvalidStateException(
                    "Redis bootstrap script response contains unexpected elements"
            );
        }
        return value;
    }

    private List<Object> requireList(Object response) {
        if (!(response instanceof List<?> rawResponse)) {
            throw new RedisBackendBootstrapInvalidStateException(
                    "Unexpected Redis bootstrap script response"
            );
        }
        return rawResponse.stream().map(Object.class::cast).toList();
    }

    private String nextString(Iterator<Object> iterator) {
        if (!iterator.hasNext()) {
            throw new RedisBackendBootstrapInvalidStateException(
                    "Redis bootstrap script response is incomplete"
            );
        }

        Object value = iterator.next();
        if (value == null) {
            throw new RedisBackendBootstrapInvalidStateException(
                    "Redis bootstrap script response contains null"
            );
        }
        return value.toString();
    }

    private long nextPositiveLong(Iterator<Object> iterator) {
        String value = nextString(iterator);
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L) {
                throw new RedisBackendBootstrapInvalidStateException(
                        "Redis bootstrap fencing token must be positive"
                );
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new RedisBackendBootstrapInvalidStateException(
                    "Redis bootstrap fencing token is not numeric",
                    exception
            );
        }
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(
                ttl,
                "ttl cannot be null"
        );
        if (nonNullTtl.isZero()
                || nonNullTtl.isNegative()
                || nonNullTtl.toMillis() <= 0L) {
            throw new IllegalArgumentException(
                    "ttl must be positive and at least one millisecond"
            );
        }
        return nonNullTtl;
    }

    private String[] acquireKeys(
            BackendBootstrapAcquireRequest request
    ) {
        ProxyMembershipLease membershipLease = request.membershipLease();
        return new String[]{
                membershipKeyspace.membershipKey(
                        membershipLease.owner().proxyName()
                ),
                bootstrapKeyspace.leaseKey(request.targetBackendName()),
                bootstrapKeyspace.requestKey(request.requestId()),
                bootstrapKeyspace.fencingCounterKey()
        };
    }

    private String[] leaseKeys(BackendBootstrapLease lease) {
        ProxyMembershipLease membershipLease = lease.ownerMembership();
        return new String[]{
                membershipKeyspace.membershipKey(
                        membershipLease.owner().proxyName()
                ),
                bootstrapKeyspace.leaseKey(lease.targetBackendName()),
                bootstrapKeyspace.requestKey(lease.requestId())
        };
    }
}

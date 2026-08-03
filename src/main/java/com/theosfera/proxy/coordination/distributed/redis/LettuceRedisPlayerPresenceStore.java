package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.DistributedPlayerPresence;
import com.theosfera.proxy.coordination.PlayerPresencePublishRequest;
import com.theosfera.proxy.coordination.PlayerPresencePublishResult;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveRequest;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class LettuceRedisPlayerPresenceStore
        implements RedisPlayerPresenceStore {

    private static final String PUBLISH_SCRIPT = """
            local sessionKey = KEYS[1]
            local presenceKey = KEYS[2]

            local playerId = ARGV[1]
            local playerName = ARGV[2]
            local authenticatedAt = ARGV[3]
            local proxyName = ARGV[4]
            local incarnationId = ARGV[5]
            local sessionFencingToken = ARGV[6]
            local backendName = ARGV[7]
            local sequence = ARGV[8]
            local observedAt = ARGV[9]
            local ttlMillis = ARGV[10]
            local occupancyPrefix = ARGV[11]

            local time = redis.call('TIME')
            local nowMillis = tonumber(time[1]) * 1000
                    + math.floor(tonumber(time[2]) / 1000)
            local expiresAt = nowMillis + tonumber(ttlMillis)

            local function occupancyKey(name)
                return occupancyPrefix .. name
            end

            local function refreshOccupancy(name)
                redis.call('ZADD', occupancyKey(name), expiresAt, playerId)
            end

            local function moveOccupancy(previousBackend, nextBackend)
                if previousBackend ~= nextBackend then
                    redis.call('ZREM', occupancyKey(previousBackend), playerId)
                end
                refreshOccupancy(nextBackend)
            end

            if redis.call('EXISTS', sessionKey) == 0 then
                return {'SESSION_NOT_FOUND'}
            end

            local sessionType = redis.call('TYPE', sessionKey)
            if type(sessionType) == 'table' then
                sessionType = sessionType['ok']
            end
            if sessionType ~= 'hash' then
                return {'CORRUPT'}
            end
            if redis.call('PTTL', sessionKey) <= 0 then
                return {'CORRUPT'}
            end

            local sessionValues = redis.call(
                'HMGET',
                sessionKey,
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
                    or sessionValues[6] == false then
                return {'CORRUPT'}
            end

            if sessionValues[1] ~= playerId then
                return {'CORRUPT'}
            end

            if sessionValues[2] ~= playerName
                    or sessionValues[3] ~= authenticatedAt
                    or sessionValues[4] ~= proxyName
                    or sessionValues[5] ~= incarnationId
                    or sessionValues[6] ~= sessionFencingToken then
                return {'NOT_SESSION_OWNER'}
            end

            if redis.call('EXISTS', presenceKey) == 0 then
                redis.call(
                    'HSET',
                    presenceKey,
                    'player-id', playerId,
                    'backend-name', backendName,
                    'proxy-name', proxyName,
                    'incarnation-id', incarnationId,
                    'session-fencing-token', sessionFencingToken,
                    'sequence', sequence,
                    'observed-at', observedAt
                )
                redis.call('PEXPIRE', presenceKey, ttlMillis)
                refreshOccupancy(backendName)
                return {'RECORDED'}
            end

            local presenceType = redis.call('TYPE', presenceKey)
            if type(presenceType) == 'table' then
                presenceType = presenceType['ok']
            end
            if presenceType ~= 'hash' then
                return {'CORRUPT'}
            end
            if redis.call('PTTL', presenceKey) <= 0 then
                return {'CORRUPT'}
            end

            local values = redis.call(
                'HMGET',
                presenceKey,
                'player-id',
                'backend-name',
                'proxy-name',
                'incarnation-id',
                'session-fencing-token',
                'sequence',
                'observed-at'
            )

            local storedToken = tonumber(values[5])
            local incomingToken = tonumber(sessionFencingToken)
            local storedSequence = tonumber(values[6])
            local incomingSequence = tonumber(sequence)
            local storedObservedAt = tonumber(values[7])
            local incomingObservedAt = tonumber(observedAt)

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or values[4] == false
                    or values[5] == false
                    or values[6] == false
                    or values[7] == false
                    or values[1] ~= playerId
                    or storedToken == nil
                    or storedToken <= 0
                    or incomingToken == nil
                    or incomingToken <= 0
                    or storedSequence == nil
                    or storedSequence <= 0
                    or incomingSequence == nil
                    or incomingSequence <= 0
                    or storedObservedAt == nil
                    or storedObservedAt <= 0
                    or incomingObservedAt == nil
                    or incomingObservedAt <= 0 then
                return {'CORRUPT'}
            end

            if storedToken > incomingToken then
                return {'CORRUPT'}
            end

            if storedToken < incomingToken then
                local previousBackend = values[2]
                redis.call(
                    'HSET',
                    presenceKey,
                    'backend-name', backendName,
                    'proxy-name', proxyName,
                    'incarnation-id', incarnationId,
                    'session-fencing-token', sessionFencingToken,
                    'sequence', sequence,
                    'observed-at', observedAt
                )
                redis.call('PEXPIRE', presenceKey, ttlMillis)
                moveOccupancy(previousBackend, backendName)
                return {'UPDATED'}
            end

            if values[3] ~= proxyName or values[4] ~= incarnationId then
                return {'CORRUPT'}
            end

            if incomingSequence < storedSequence then
                return {'STALE'}
            end

            if incomingSequence > storedSequence then
                local previousBackend = values[2]
                redis.call(
                    'HSET',
                    presenceKey,
                    'backend-name', backendName,
                    'sequence', sequence,
                    'observed-at', observedAt
                )
                redis.call('PEXPIRE', presenceKey, ttlMillis)
                moveOccupancy(previousBackend, backendName)
                return {'UPDATED'}
            end

            if values[2] == backendName and values[7] == observedAt then
                redis.call('PEXPIRE', presenceKey, ttlMillis)
                refreshOccupancy(backendName)
                return {'ALREADY_RECORDED'}
            end

            return {'CONFLICT'}
            """;

    private static final String FIND_SCRIPT = """
            local presenceKey = KEYS[1]

            if redis.call('EXISTS', presenceKey) == 0 then
                return {'NOT_FOUND'}
            end

            local presenceType = redis.call('TYPE', presenceKey)
            if type(presenceType) == 'table' then
                presenceType = presenceType['ok']
            end
            if presenceType ~= 'hash' then
                return {'CORRUPT'}
            end
            if redis.call('PTTL', presenceKey) <= 0 then
                return {'CORRUPT'}
            end

            local values = redis.call(
                'HMGET',
                presenceKey,
                'player-id',
                'backend-name',
                'proxy-name',
                'incarnation-id',
                'session-fencing-token',
                'sequence',
                'observed-at'
            )

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or values[4] == false
                    or values[5] == false
                    or values[6] == false
                    or values[7] == false then
                return {'CORRUPT'}
            end

            return {
                'FOUND',
                values[1],
                values[2],
                values[3],
                values[4],
                values[5],
                values[6],
                values[7]
            }
            """;

    private static final String REMOVE_SCRIPT = """
            local sessionKey = KEYS[1]
            local presenceKey = KEYS[2]

            local playerId = ARGV[1]
            local playerName = ARGV[2]
            local authenticatedAt = ARGV[3]
            local proxyName = ARGV[4]
            local incarnationId = ARGV[5]
            local sessionFencingToken = ARGV[6]
            local backendName = ARGV[7]
            local sequence = ARGV[8]
            local occupancyPrefix = ARGV[9]

            if redis.call('EXISTS', sessionKey) == 0 then
                return {'SESSION_NOT_FOUND'}
            end

            local sessionType = redis.call('TYPE', sessionKey)
            if type(sessionType) == 'table' then
                sessionType = sessionType['ok']
            end
            if sessionType ~= 'hash' then
                return {'CORRUPT'}
            end
            if redis.call('PTTL', sessionKey) <= 0 then
                return {'CORRUPT'}
            end

            local sessionValues = redis.call(
                'HMGET',
                sessionKey,
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
                    or sessionValues[6] ~= sessionFencingToken then
                return {'NOT_SESSION_OWNER'}
            end

            if redis.call('EXISTS', presenceKey) == 0 then
                return {'NOT_FOUND'}
            end

            local presenceType = redis.call('TYPE', presenceKey)
            if type(presenceType) == 'table' then
                presenceType = presenceType['ok']
            end
            if presenceType ~= 'hash' then
                return {'CORRUPT'}
            end
            if redis.call('PTTL', presenceKey) <= 0 then
                return {'CORRUPT'}
            end

            local values = redis.call(
                'HMGET',
                presenceKey,
                'player-id',
                'backend-name',
                'proxy-name',
                'incarnation-id',
                'session-fencing-token',
                'sequence',
                'observed-at'
            )

            local storedToken = tonumber(values[5])
            local incomingToken = tonumber(sessionFencingToken)
            local storedSequence = tonumber(values[6])
            local incomingSequence = tonumber(sequence)

            if values[1] == false
                    or values[2] == false
                    or values[3] == false
                    or values[4] == false
                    or values[5] == false
                    or values[6] == false
                    or values[7] == false
                    or values[1] ~= playerId
                    or storedToken == nil
                    or incomingToken == nil
                    or storedSequence == nil
                    or incomingSequence == nil then
                return {'CORRUPT'}
            end

            if storedToken > incomingToken then
                return {'CORRUPT'}
            end
            if storedToken < incomingToken then
                return {'STALE'}
            end
            if values[3] ~= proxyName or values[4] ~= incarnationId then
                return {'CORRUPT'}
            end
            if incomingSequence < storedSequence then
                return {'STALE'}
            end
            if incomingSequence > storedSequence then
                return {'CONFLICT'}
            end
            if values[2] ~= backendName then
                return {'CONFLICT'}
            end

            redis.call('DEL', presenceKey)
            redis.call('ZREM', occupancyPrefix .. backendName, playerId)
            return {'REMOVED'}
            """;

    private final RedisScriptingAsyncCommands<String, String> commands;
    private final RedisPlayerPresenceKeyspace presenceKeyspace;
    private final RedisPlayerSessionKeyspace sessionKeyspace;
    private final RedisBackendOccupancyKeyspace occupancyKeyspace;

    public LettuceRedisPlayerPresenceStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisPlayerPresenceKeyspace presenceKeyspace,
            RedisPlayerSessionKeyspace sessionKeyspace
    ) {
        this(
                commands,
                presenceKeyspace,
                sessionKeyspace,
                RedisBackendOccupancyKeyspace.defaultKeyspace()
        );
    }

    public LettuceRedisPlayerPresenceStore(
            RedisScriptingAsyncCommands<String, String> commands,
            RedisPlayerPresenceKeyspace presenceKeyspace,
            RedisPlayerSessionKeyspace sessionKeyspace,
            RedisBackendOccupancyKeyspace occupancyKeyspace
    ) {
        this.commands = Objects.requireNonNull(
                commands,
                "commands cannot be null"
        );
        this.presenceKeyspace = Objects.requireNonNull(
                presenceKeyspace,
                "presenceKeyspace cannot be null"
        );
        this.sessionKeyspace = Objects.requireNonNull(
                sessionKeyspace,
                "sessionKeyspace cannot be null"
        );
        this.occupancyKeyspace = Objects.requireNonNull(
                occupancyKeyspace,
                "occupancyKeyspace cannot be null"
        );
    }

    @Override
    public CompletionStage<PlayerPresencePublishResult> publish(
            PlayerPresencePublishRequest request,
            Duration ttl
    ) {
        PlayerPresencePublishRequest nonNullRequest =
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                );
        Duration nonNullTtl = requirePositiveTtl(ttl);

        return commands.eval(
                PUBLISH_SCRIPT,
                ScriptOutputType.MULTI,
                keys(nonNullRequest.sessionLease().session().playerId()),
                publishArguments(nonNullRequest, nonNullTtl)
        ).thenApply(
                response -> mapPublishResponse(
                        nonNullRequest,
                        requireList(response)
                )
        );
    }

    @Override
    public CompletionStage<Optional<DistributedPlayerPresence>> find(
            UUID playerId
    ) {
        UUID nonNullPlayerId = Objects.requireNonNull(
                playerId,
                "playerId cannot be null"
        );

        return commands.eval(
                FIND_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        presenceKeyspace.playerPresenceKey(nonNullPlayerId)
                }
        ).thenApply(response -> mapFindResponse(requireList(response)));
    }

    @Override
    public CompletionStage<PlayerPresenceRemoveResult> removeIfOwned(
            PlayerPresenceRemoveRequest request
    ) {
        PlayerPresenceRemoveRequest nonNullRequest =
                Objects.requireNonNull(
                        request,
                        "request cannot be null"
                );

        return commands.eval(
                REMOVE_SCRIPT,
                ScriptOutputType.MULTI,
                keys(nonNullRequest.sessionLease().session().playerId()),
                removeArguments(nonNullRequest)
        ).thenApply(
                response -> mapRemoveResponse(requireList(response))
        );
    }

    private PlayerPresencePublishResult mapPublishResponse(
            PlayerPresencePublishRequest request,
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        String status = nextString(iterator);
        requireComplete(iterator);

        return switch (status) {
            case "RECORDED" -> PlayerPresencePublishResult.withPresence(
                    PlayerPresencePublishResult.Status.RECORDED,
                    request.presence()
            );
            case "UPDATED" -> PlayerPresencePublishResult.withPresence(
                    PlayerPresencePublishResult.Status.UPDATED,
                    request.presence()
            );
            case "ALREADY_RECORDED" ->
                    PlayerPresencePublishResult.withPresence(
                            PlayerPresencePublishResult.Status
                                    .ALREADY_RECORDED,
                            request.presence()
                    );
            case "STALE" -> PlayerPresencePublishResult.withoutPresence(
                    PlayerPresencePublishResult.Status.STALE
            );
            case "CONFLICT" -> PlayerPresencePublishResult.withoutPresence(
                    PlayerPresencePublishResult.Status.CONFLICT
            );
            case "SESSION_NOT_FOUND" ->
                    PlayerPresencePublishResult.withoutPresence(
                            PlayerPresencePublishResult.Status
                                    .SESSION_NOT_FOUND
                    );
            case "NOT_SESSION_OWNER" ->
                    PlayerPresencePublishResult.withoutPresence(
                            PlayerPresencePublishResult.Status
                                    .NOT_SESSION_OWNER
                    );
            case "CORRUPT" -> throw invalidState();
            default -> throw new RedisPlayerPresenceInvalidStateException(
                    "Redis presence publish returned unknown status"
            );
        };
    }

    private Optional<DistributedPlayerPresence> mapFindResponse(
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        String status = nextString(iterator);

        if ("NOT_FOUND".equals(status)) {
            requireComplete(iterator);
            return Optional.empty();
        }
        if ("CORRUPT".equals(status)) {
            throw invalidState();
        }
        if (!"FOUND".equals(status)) {
            throw new RedisPlayerPresenceInvalidStateException(
                    "Redis presence find returned unknown status"
            );
        }

        DistributedPlayerPresence presence =
                new DistributedPlayerPresence(
                        UUID.fromString(nextString(iterator)),
                        nextString(iterator),
                        new com.theosfera.proxy.coordination.ProxyInstanceIdentity(
                                nextString(iterator),
                                UUID.fromString(nextString(iterator))
                        ),
                        nextPositiveLong(iterator),
                        nextPositiveLong(iterator),
                        nextPositiveLong(iterator)
                );
        requireComplete(iterator);
        return Optional.of(presence);
    }

    private PlayerPresenceRemoveResult mapRemoveResponse(
            List<Object> response
    ) {
        Iterator<Object> iterator = response.iterator();
        String status = nextString(iterator);
        requireComplete(iterator);

        return new PlayerPresenceRemoveResult(
                switch (status) {
                    case "REMOVED" ->
                            PlayerPresenceRemoveResult.Status.REMOVED;
                    case "NOT_FOUND" ->
                            PlayerPresenceRemoveResult.Status.NOT_FOUND;
                    case "STALE" ->
                            PlayerPresenceRemoveResult.Status.STALE;
                    case "CONFLICT" ->
                            PlayerPresenceRemoveResult.Status.CONFLICT;
                    case "SESSION_NOT_FOUND" ->
                            PlayerPresenceRemoveResult.Status
                                    .SESSION_NOT_FOUND;
                    case "NOT_SESSION_OWNER" ->
                            PlayerPresenceRemoveResult.Status
                                    .NOT_SESSION_OWNER;
                    case "CORRUPT" -> throw invalidState();
                    default -> throw new RedisPlayerPresenceInvalidStateException(
                            "Redis presence remove returned unknown status"
                    );
                }
        );
    }

    private String[] keys(UUID playerId) {
        return new String[]{
                sessionKeyspace.playerSessionKey(playerId),
                presenceKeyspace.playerPresenceKey(playerId)
        };
    }

    private String[] publishArguments(
            PlayerPresencePublishRequest request,
            Duration ttl
    ) {
        DistributedPlayerPresence presence = request.presence();
        PlayerSessionLease lease = request.sessionLease();

        return new String[]{
                presence.playerId().toString(),
                lease.session().playerName(),
                Long.toString(lease.session().authenticatedAt()),
                presence.owner().proxyName(),
                presence.owner().incarnationId().toString(),
                Long.toString(presence.sessionFencingToken()),
                presence.backendName(),
                Long.toString(presence.sequence()),
                Long.toString(presence.observedAt()),
                Long.toString(ttl.toMillis()),
                occupancyKeyspace.backendPresenceIndexPrefix()
        };
    }

    private String[] removeArguments(PlayerPresenceRemoveRequest request) {
        PlayerSessionLease lease = request.sessionLease();
        return new String[]{
                lease.session().playerId().toString(),
                lease.session().playerName(),
                Long.toString(lease.session().authenticatedAt()),
                lease.owner().proxyName(),
                lease.owner().incarnationId().toString(),
                Long.toString(lease.fencingToken()),
                request.backendName(),
                Long.toString(request.sequence()),
                occupancyKeyspace.backendPresenceIndexPrefix()
        };
    }

    private Duration requirePositiveTtl(Duration ttl) {
        Duration nonNullTtl = Objects.requireNonNull(
                ttl,
                "ttl cannot be null"
        );
        if (nonNullTtl.isZero() || nonNullTtl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return nonNullTtl;
    }

    private List<Object> requireList(Object response) {
        if (!(response instanceof List<?> rawResponse)) {
            throw new RedisPlayerPresenceInvalidStateException(
                    "Unexpected Redis presence script response"
            );
        }
        return rawResponse.stream().map(Object.class::cast).toList();
    }

    private String nextString(Iterator<Object> iterator) {
        if (!iterator.hasNext()) {
            throw new RedisPlayerPresenceInvalidStateException(
                    "Redis presence script response is incomplete"
            );
        }
        Object value = iterator.next();
        if (value == null) {
            throw new RedisPlayerPresenceInvalidStateException(
                    "Redis presence script response contains null"
            );
        }
        return value.toString();
    }

    private long nextPositiveLong(Iterator<Object> iterator) {
        String value = nextString(iterator);
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new RedisPlayerPresenceInvalidStateException(
                        "Redis presence numeric field must be positive"
                );
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new RedisPlayerPresenceInvalidStateException(
                    "Redis presence numeric field is not numeric",
                    exception
            );
        }
    }

    private void requireComplete(Iterator<Object> iterator) {
        if (iterator.hasNext()) {
            throw new RedisPlayerPresenceInvalidStateException(
                    "Redis presence script response has extra elements"
            );
        }
    }

    private RedisPlayerPresenceInvalidStateException invalidState() {
        return new RedisPlayerPresenceInvalidStateException(
                "Redis player presence state is corrupt"
        );
    }
}

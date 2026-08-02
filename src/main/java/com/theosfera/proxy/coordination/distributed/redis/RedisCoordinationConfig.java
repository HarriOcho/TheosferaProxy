package com.theosfera.proxy.coordination.distributed.redis;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public record RedisCoordinationConfig(
        String redisUri,
        Duration membershipTtl,
        Duration membershipRenewInterval
) {

    public RedisCoordinationConfig {
        redisUri = requireRedisUri(redisUri);
        membershipTtl = requirePositive(
                membershipTtl,
                "membershipTtl"
        );
        membershipRenewInterval = requirePositive(
                membershipRenewInterval,
                "membershipRenewInterval"
        );

        if (membershipRenewInterval.compareTo(membershipTtl) >= 0) {
            throw new IllegalArgumentException(
                    "membershipRenewInterval must be shorter than membershipTtl"
            );
        }
    }

    private static String requireRedisUri(String value) {
        String nonBlank = Objects.requireNonNull(
                value,
                "redisUri cannot be null"
        ).trim();

        if (nonBlank.isEmpty()) {
            throw new IllegalArgumentException("redisUri cannot be blank");
        }

        URI uri;
        try {
            uri = URI.create(nonBlank);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "redisUri is invalid",
                    exception
            );
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException(
                    "redisUri must include redis:// or rediss://"
            );
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("redis")
                && !normalizedScheme.equals("rediss")) {
            throw new IllegalArgumentException(
                    "redisUri must use redis:// or rediss://"
            );
        }

        return nonBlank;
    }

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        Duration nonNullValue = Objects.requireNonNull(
                value,
                name + " cannot be null"
        );

        if (nonNullValue.isZero()
                || nonNullValue.isNegative()
                || nonNullValue.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }

        return nonNullValue;
    }
}

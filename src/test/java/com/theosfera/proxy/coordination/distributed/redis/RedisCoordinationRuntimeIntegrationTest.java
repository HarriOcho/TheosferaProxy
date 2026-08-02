package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.CoordinationStateRegistry;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipRenewalScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Testcontainers
@ExtendWith(MockitoExtension.class)
class RedisCoordinationRuntimeIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    @Test
    void startAcquiresMembershipAndStopReleasesIt() {
        RedisCoordinationConfig config = config();
        ProxyInstanceIdentity identity = identity("proxy-runtime");
        CoordinationStateRegistry states = new CoordinationStateRegistry();
        ManualScheduler scheduler = new ManualScheduler();

        RedisCoordinationRuntime runtime = new RedisCoordinationRuntime(
                config,
                identity,
                scheduler,
                states,
                Clock.systemUTC(),
                mock(Logger.class)
        );

        assertTrue(runtime.start().toCompletableFuture().join());
        assertEquals(CoordinationState.HEALTHY, states.get());
        assertTrue(runtime.membershipLifecycle().currentLease() != null);

        assertTrue(runtime.stop().toCompletableFuture().join());
        assertEquals(CoordinationState.STOPPING, states.get());

        CoordinationStateRegistry replacementStates =
                new CoordinationStateRegistry();
        RedisCoordinationRuntime replacement = new RedisCoordinationRuntime(
                config,
                identity("proxy-runtime"),
                new ManualScheduler(),
                replacementStates,
                Clock.systemUTC(),
                mock(Logger.class)
        );

        assertTrue(replacement.start().toCompletableFuture().join());
        assertEquals(CoordinationState.HEALTHY, replacementStates.get());
        replacement.stop().toCompletableFuture().join();
    }

    @Test
    void secondIncarnationCannotStartWhileMembershipIsOwned() {
        RedisCoordinationConfig config = config();
        RedisCoordinationRuntime first = runtime(config, identity("proxy-collision"));
        RedisCoordinationRuntime second = runtime(config, identity("proxy-collision"));

        assertTrue(first.start().toCompletableFuture().join());
        assertFalse(second.start().toCompletableFuture().join());

        first.stop().toCompletableFuture().join();
        second.stop().toCompletableFuture().join();
    }

    private RedisCoordinationRuntime runtime(
            RedisCoordinationConfig config,
            ProxyInstanceIdentity identity
    ) {
        return new RedisCoordinationRuntime(
                config,
                identity,
                new ManualScheduler(),
                new CoordinationStateRegistry(),
                Clock.systemUTC(),
                mock(Logger.class)
        );
    }

    private RedisCoordinationConfig config() {
        return new RedisCoordinationConfig(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5)
        );
    }

    private ProxyInstanceIdentity identity(String proxyName) {
        return new ProxyInstanceIdentity(proxyName, UUID.randomUUID());
    }

    private static final class ManualScheduler
            implements ProxyMembershipRenewalScheduler {

        @Override
        public Handle schedule(Runnable task, Duration interval) {
            return () -> { };
        }
    }
}

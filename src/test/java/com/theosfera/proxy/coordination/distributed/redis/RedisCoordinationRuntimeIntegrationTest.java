package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.CoordinationStateRegistry;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipRenewalScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RedisCoordinationRuntimeIntegrationTest {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.4.2-alpine");

    private static GenericContainer<?> redis;

    @BeforeAll
    static void startRedis() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            String message =
                    "Docker is not available for Redis Testcontainers";

            if (RedisTestcontainersSupport
                    .shouldFailWhenDockerUnavailable()) {
                throw new IllegalStateException(message);
            }

            Assumptions.assumeTrue(false, message);
        }

        redis = new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(6379);
        redis.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void startAcquiresMembershipAndStopReleasesIt() throws Exception {
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

        assertTrue(await(runtime.start()));
        assertEquals(CoordinationState.HEALTHY, states.get());
        assertTrue(runtime.membershipLifecycle().currentLease() != null);

        assertTrue(await(runtime.stop()));
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

        assertTrue(await(replacement.start()));
        assertEquals(CoordinationState.HEALTHY, replacementStates.get());
        assertTrue(await(replacement.stop()));
    }

    @Test
    void secondIncarnationCannotStartWhileMembershipIsOwned() throws Exception {
        RedisCoordinationConfig config = config();
        RedisCoordinationRuntime first = runtime(
                config,
                identity("proxy-collision")
        );
        RedisCoordinationRuntime second = runtime(
                config,
                identity("proxy-collision")
        );

        assertTrue(await(first.start()));
        assertFalse(await(second.start()));

        assertTrue(await(first.stop()));
        assertTrue(await(second.stop()));
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
                "redis://"
                        + redis.getHost()
                        + ":"
                        + redis.getMappedPort(6379),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5)
        );
    }

    private ProxyInstanceIdentity identity(String proxyName) {
        return new ProxyInstanceIdentity(
                proxyName,
                UUID.randomUUID()
        );
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static final class ManualScheduler
            implements ProxyMembershipRenewalScheduler {

        @Override
        public Handle schedule(Runnable task, Duration interval) {
            return () -> { };
        }
    }
}

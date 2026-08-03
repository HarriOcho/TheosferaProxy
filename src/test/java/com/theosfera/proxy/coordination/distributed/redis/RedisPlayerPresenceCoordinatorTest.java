package com.theosfera.proxy.coordination.distributed.redis;

import com.theosfera.proxy.coordination.DistributedPlayerPresence;
import com.theosfera.proxy.coordination.PlayerPresencePublishRequest;
import com.theosfera.proxy.coordination.PlayerPresencePublishResult;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveRequest;
import com.theosfera.proxy.coordination.PlayerPresenceRemoveResult;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisPlayerPresenceCoordinatorTest {

    @Test
    void mapsTransportFailuresToCoordinationUnavailable() {
        RedisPlayerPresenceStore store = mock(RedisPlayerPresenceStore.class);
        RuntimeException failure = new RuntimeException("redis unavailable");

        when(store.publish(any(), eq(Duration.ofSeconds(30))))
                .thenReturn(CompletableFuture.failedFuture(failure));
        when(store.removeIfOwned(any()))
                .thenReturn(CompletableFuture.failedFuture(failure));

        RedisPlayerPresenceCoordinator coordinator =
                new RedisPlayerPresenceCoordinator(
                        store,
                        Duration.ofSeconds(30)
                );

        assertEquals(
                PlayerPresencePublishResult.Status.COORDINATION_UNAVAILABLE,
                coordinator.publish(publishRequest())
                        .toCompletableFuture()
                        .join()
                        .status()
        );
        assertEquals(
                PlayerPresenceRemoveResult.Status.COORDINATION_UNAVAILABLE,
                coordinator.removeIfOwned(removeRequest())
                        .toCompletableFuture()
                        .join()
                        .status()
        );
    }

    @Test
    void propagatesCorruptRedisState() {
        RedisPlayerPresenceStore store = mock(RedisPlayerPresenceStore.class);
        RedisPlayerPresenceInvalidStateException failure =
                new RedisPlayerPresenceInvalidStateException("corrupt");

        when(store.publish(any(), eq(Duration.ofSeconds(30))))
                .thenReturn(CompletableFuture.failedFuture(failure));

        RedisPlayerPresenceCoordinator coordinator =
                new RedisPlayerPresenceCoordinator(
                        store,
                        Duration.ofSeconds(30)
                );

        CompletionException thrown = assertThrows(
                CompletionException.class,
                () -> coordinator.publish(publishRequest())
                        .toCompletableFuture()
                        .join()
        );
        assertEquals(failure, thrown.getCause());
    }

    @Test
    void findDoesNotConvertUnavailableIntoAbsence() {
        RedisPlayerPresenceStore store = mock(RedisPlayerPresenceStore.class);
        RuntimeException failure = new RuntimeException("redis unavailable");
        UUID playerId = lease().session().playerId();

        when(store.find(playerId))
                .thenReturn(CompletableFuture.failedFuture(failure));

        RedisPlayerPresenceCoordinator coordinator =
                new RedisPlayerPresenceCoordinator(
                        store,
                        Duration.ofSeconds(30)
                );

        CompletionException thrown = assertThrows(
                CompletionException.class,
                () -> coordinator.find(playerId)
                        .toCompletableFuture()
                        .join()
        );
        assertEquals(failure, thrown.getCause());
    }

    @Test
    void forwardsSuccessfulFind() {
        RedisPlayerPresenceStore store = mock(RedisPlayerPresenceStore.class);
        DistributedPlayerPresence presence = publishRequest().presence();

        when(store.find(presence.playerId()))
                .thenReturn(CompletableFuture.completedFuture(
                        Optional.of(presence)
                ));

        RedisPlayerPresenceCoordinator coordinator =
                new RedisPlayerPresenceCoordinator(
                        store,
                        Duration.ofSeconds(30)
                );

        assertEquals(
                Optional.of(presence),
                coordinator.find(presence.playerId())
                        .toCompletableFuture()
                        .join()
        );
    }

    private PlayerPresencePublishRequest publishRequest() {
        return new PlayerPresencePublishRequest(
                lease(),
                "lobby-1",
                1L,
                1_000L
        );
    }

    private PlayerPresenceRemoveRequest removeRequest() {
        return new PlayerPresenceRemoveRequest(
                lease(),
                "lobby-1",
                1L
        );
    }

    private PlayerSessionLease lease() {
        return new PlayerSessionLease(
                new AuthenticatedPlayerSession(
                        UUID.fromString(
                                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
                        ),
                        "HarriOcho",
                        500L
                ),
                new ProxyInstanceIdentity(
                        "proxy-1",
                        UUID.fromString(
                                "d505feca-365c-4fb4-818e-3efccf124d97"
                        )
                ),
                7L
        );
    }
}

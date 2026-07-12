package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerTransferExecutorTest {

    private Player player;
    private RegisteredServer target;
    private ConnectionRequestBuilder requestBuilder;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        target = mock(RegisteredServer.class);
        requestBuilder =
                mock(ConnectionRequestBuilder.class);

        when(player.createConnectionRequest(target))
                .thenReturn(requestBuilder);
    }

    @Test
    void reportsSuccessfulConnection() {
        ConnectionRequestBuilder.Result result =
                mock(ConnectionRequestBuilder.Result.class);

        when(result.isSuccessful()).thenReturn(true);
        when(requestBuilder.connect())
                .thenReturn(
                        CompletableFuture.completedFuture(result)
                );

        PlayerTransferCompletion completion =
                new PlayerTransferExecutor()
                        .execute(player, target)
                        .join();

        assertEquals(
                TransferResultStatus.SUCCESS,
                completion.status()
        );
    }

    @Test
    void reportsRejectedConnection() {
        ConnectionRequestBuilder.Result result =
                mock(ConnectionRequestBuilder.Result.class);

        when(result.isSuccessful()).thenReturn(false);
        when(requestBuilder.connect())
                .thenReturn(
                        CompletableFuture.completedFuture(result)
                );

        PlayerTransferCompletion completion =
                new PlayerTransferExecutor()
                        .execute(player, target)
                        .join();

        assertEquals(
                TransferResultStatus.REJECTED,
                completion.status()
        );
    }

    @Test
    void reportsFailedConnection() {
        CompletableFuture<ConnectionRequestBuilder.Result>
                failedFuture = new CompletableFuture<>();

        failedFuture.completeExceptionally(
                new IllegalStateException("connection failed")
        );

        when(requestBuilder.connect())
                .thenReturn(failedFuture);

        PlayerTransferCompletion completion =
                new PlayerTransferExecutor()
                        .execute(player, target)
                        .join();

        assertEquals(
                TransferResultStatus.FAILED,
                completion.status()
        );
    }

    @Test
    void reportsSynchronousConnectionFailure() {
        when(player.createConnectionRequest(target))
                .thenThrow(
                        new IllegalStateException(
                                "connection request failed"
                        )
                );

        PlayerTransferCompletion completion =
                new PlayerTransferExecutor()
                        .execute(player, target)
                        .join();

        assertEquals(
                TransferResultStatus.FAILED,
                completion.status()
        );
    }

    @Test
    void reportsTimedOutConnection() throws Exception {
        CompletableFuture<ConnectionRequestBuilder.Result>
                pendingFuture = new CompletableFuture<>();

        when(requestBuilder.connect())
                .thenReturn(pendingFuture);

        PlayerTransferCompletion completion =
                new PlayerTransferExecutor(
                        Duration.ofMillis(10)
                )
                        .execute(player, target)
                        .get(1, TimeUnit.SECONDS);

        assertEquals(
                TransferResultStatus.TIMED_OUT,
                completion.status()
        );
    }

    @Test
    void rejectsInvalidInputsAndTimeout() {
        assertThrows(
                NullPointerException.class,
                () -> new PlayerTransferExecutor(null)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerTransferExecutor(
                        Duration.ZERO
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PlayerTransferExecutor(
                        Duration.ofSeconds(-1)
                )
        );

        PlayerTransferExecutor executor =
                new PlayerTransferExecutor();

        assertThrows(
                NullPointerException.class,
                () -> executor.execute(null, target)
        );

        assertThrows(
                NullPointerException.class,
                () -> executor.execute(player, null)
        );
    }
}

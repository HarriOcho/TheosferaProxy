package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistrationResult;
import com.theosfera.proxy.transfer.DistributedPlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferRegistrationResult;
import com.theosfera.proxy.transfer.PlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.PlayerTransferTargetAllocationService;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyTransferServiceTest {

    private static final UUID PLAYER_ID = UUID.fromString(
            "417e98b4-74a1-467e-b453-a15be3af8996"
    );
    private static final UUID REQUEST_ID = UUID.fromString(
            "11111111-2222-3333-4444-555555555555"
    );
    private static final long NOW = 1_750_000_000_000L;

    private AuthenticatedPlayerSessionRegistry sessionRegistry;
    private DistributedPlayerTransferRetryCoordinator retryCoordinator;
    private Player player;
    private LobbyTransferService service;

    @BeforeEach
    void setUp() {
        sessionRegistry = new AuthenticatedPlayerSessionRegistry();
        retryCoordinator = mock(DistributedPlayerTransferRetryCoordinator.class);
        player = mock(Player.class);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        service = new LobbyTransferService(
                sessionRegistry,
                retryCoordinator,
                Clock.fixed(
                        Instant.ofEpochMilli(NOW),
                        ZoneOffset.UTC
                ),
                () -> REQUEST_ID
        );
    }

    @Test
    void dependsOnDistributedRetryWithoutLegacyLobbyAllocationFields() {
        Set<Class<?>> fieldTypes = Arrays.stream(
                        LobbyTransferService.class.getDeclaredFields()
                )
                .map(Field::getType)
                .collect(Collectors.toSet());

        assertTrue(
                fieldTypes.contains(
                        DistributedPlayerTransferRetryCoordinator.class
                )
        );
        assertFalse(fieldTypes.contains(PlayerTransferRetryCoordinator.class));
        assertFalse(
                fieldTypes.contains(PlayerTransferTargetAllocationService.class)
        );
        assertFalse(fieldTypes.contains(TransferTargetResolver.class));
    }

    @Test
    void rejectsUnauthenticatedPlayerBeforeDistributedAllocation() {
        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.AUTHENTICATION_REQUIRED_MESSAGE
        );
        verify(retryCoordinator, never()).start(any());
    }

    @Test
    void rejectsPlayerWithoutCurrentServerBeforeDistributedAllocation() {
        authenticatePlayer();
        when(player.getCurrentServer()).thenReturn(Optional.empty());

        service.transferToLobby(player);

        verify(player).sendMessage(
                LobbyTransferService.NO_CURRENT_SERVER_MESSAGE
        );
        verify(retryCoordinator, never()).start(any());
    }

    @Test
    void delegatesLobbyRequestToDistributedRetryCoordinator() {
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRequest("skyblock-1");

        assertEquals(REQUEST_ID, request.requestId());
        assertEquals(PLAYER_ID, request.playerId());
        assertEquals("skyblock-1", request.sourceBackendName());
        assertEquals(BackendType.LOBBY, request.targetBackendType());
        assertEquals(NOW, request.requestedAt());
        assertSame(player, request.player());
    }

    @Test
    void sameTargetKeepsExistingLobbyMessage() {
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRequest("lobby-1");

        request.sameTargetHandler().run();

        verify(player).sendMessage(
                LobbyTransferService.ALREADY_IN_LOBBY_MESSAGE
        );
    }

    @Test
    void registrationRejectionsKeepPendingTransferMessage() {
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRequest("skyblock-1");

        request.registrationRejectedHandler().accept(
                PlayerTransferRegistrationResult.ALREADY_REGISTERED
        );
        request.registrationRejectedHandler().accept(
                PlayerTransferRegistrationResult.PLAYER_BUSY
        );
        request.registrationRejectedHandler().accept(
                PlayerTransferRegistrationResult.REQUEST_ID_CONFLICT
        );

        verify(player, times(3)).sendMessage(
                LobbyTransferService.TRANSFER_PENDING_MESSAGE
        );
        assertThrows(
                IllegalStateException.class,
                () -> request.registrationRejectedHandler().accept(
                        PlayerTransferRegistrationResult.REGISTERED
                )
        );
    }

    @Test
    void unavailableTargetsKeepLobbyUnavailableMessage() {
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRequest("skyblock-1");

        request.unavailableHandler().accept(
                TransferTargetResolution.notConfigured()
        );
        request.unavailableHandler().accept(
                TransferTargetResolution.notAuthenticated()
        );
        request.unavailableHandler().accept(
                TransferTargetResolution.noCapacity()
        );

        verify(player, times(3)).sendMessage(
                LobbyTransferService.LOBBY_UNAVAILABLE_MESSAGE
        );
    }

    @Test
    void distributedCapacityRejectionsFailClosedWithLobbyUnavailableMessage() {
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRequest("skyblock-1");

        for (BackendCapacityReserveResult.Status status : Set.of(
                BackendCapacityReserveResult.Status.NO_CAPACITY,
                BackendCapacityReserveResult.Status.REQUEST_ID_CONFLICT,
                BackendCapacityReserveResult.Status.SESSION_NOT_FOUND,
                BackendCapacityReserveResult.Status.NOT_SESSION_OWNER,
                BackendCapacityReserveResult.Status.OCCUPANCY_UNAVAILABLE,
                BackendCapacityReserveResult.Status.COORDINATION_UNAVAILABLE
        )) {
            request.capacityRejectedHandler().accept(status);
        }

        verify(player, times(6)).sendMessage(
                LobbyTransferService.LOBBY_UNAVAILABLE_MESSAGE
        );
        assertThrows(
                IllegalStateException.class,
                () -> request.capacityRejectedHandler().accept(
                        BackendCapacityReserveResult.Status.RESERVED
                )
        );
    }

    @Test
    void bootstrapRejectionsKeepLobbyUnavailableMessage() {
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRequest("skyblock-1");

        request.bootstrapRejectedHandler().accept(
                BackendBootstrapRegistrationResult.TARGET_BUSY
        );
        request.bootstrapRejectedHandler().accept(
                BackendBootstrapRegistrationResult.REQUEST_ID_CONFLICT
        );
        request.bootstrapRejectedHandler().accept(
                BackendBootstrapRegistrationResult.ALREADY_RESERVED
        );

        verify(player, times(3)).sendMessage(
                LobbyTransferService.LOBBY_UNAVAILABLE_MESSAGE
        );
        assertThrows(
                IllegalStateException.class,
                () -> request.bootstrapRejectedHandler().accept(
                        BackendBootstrapRegistrationResult.RESERVED
                )
        );
    }

    @Test
    void completionMessagesRemainStable() {
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRequest("skyblock-1");

        request.completionHandler().accept(
                PlayerTransferCompletion.success()
        );
        request.completionHandler().accept(
                PlayerTransferCompletion.rejected()
        );
        request.completionHandler().accept(
                PlayerTransferCompletion.failed()
        );
        request.completionHandler().accept(
                PlayerTransferCompletion.timedOut()
        );

        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_SUCCESS_MESSAGE
        );
        verify(player, times(2)).sendMessage(
                LobbyTransferService.TRANSFER_FAILED_MESSAGE
        );
        verify(player).sendMessage(
                LobbyTransferService.TRANSFER_TIMED_OUT_MESSAGE
        );
    }

    @Test
    void successReservationAndLateResultCallbacksStaySilentAtCommandLayer() {
        DistributedPlayerTransferRetryCoordinator.TransferRetryRequest request =
                captureRequest("skyblock-1");

        request.bootstrapReservedHandler().accept(
                new com.theosfera.proxy.transfer.BackendBootstrapReservation(
                        "lobby-1",
                        REQUEST_ID,
                        PLAYER_ID,
                        NOW
                )
        );
        request.lateResultHandler().accept(
                new com.theosfera.proxy.transfer.PendingPlayerTransfer(
                        REQUEST_ID,
                        PLAYER_ID,
                        "skyblock-1",
                        "lobby-1",
                        NOW
                )
        );

        verify(player, never()).sendMessage(any(Component.class));
    }

    private DistributedPlayerTransferRetryCoordinator.TransferRetryRequest
    captureRequest(String sourceBackendName) {
        authenticatePlayer();
        when(player.getCurrentServer()).thenReturn(
                Optional.of(serverConnection(sourceBackendName))
        );

        service.transferToLobby(player);

        ArgumentCaptor<DistributedPlayerTransferRetryCoordinator.TransferRetryRequest>
                captor = ArgumentCaptor.forClass(
                DistributedPlayerTransferRetryCoordinator
                        .TransferRetryRequest.class
        );
        verify(retryCoordinator).start(captor.capture());
        return captor.getValue();
    }

    private void authenticatePlayer() {
        sessionRegistry.register(
                new AuthenticatedPlayerSession(
                        PLAYER_ID,
                        "HarriOcho",
                        NOW - 200
                )
        );
    }

    private ServerConnection serverConnection(String serverName) {
        ServerConnection connection = mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(connection.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(serverName);

        return connection;
    }
}

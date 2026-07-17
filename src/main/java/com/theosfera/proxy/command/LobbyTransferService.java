package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.PlayerTransferRegistrationResult;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolutionStatus;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class LobbyTransferService {

    static final Component AUTHENTICATION_REQUIRED_MESSAGE =
            Component.text(
                    "Debes autenticarte antes de usar este comando."
            );

    static final Component NO_CURRENT_SERVER_MESSAGE =
            Component.text(
                    "No se pudo confirmar tu servidor actual."
            );

    static final Component LOBBY_UNAVAILABLE_MESSAGE =
            Component.text(
                    "El Lobby no está disponible ahora."
            );

    static final Component ALREADY_IN_LOBBY_MESSAGE =
            Component.text(
                    "Ya estás en el Lobby."
            );

    static final Component TRANSFER_PENDING_MESSAGE =
            Component.text(
                    "Ya tienes una transferencia pendiente."
            );

    static final Component TRANSFER_SUCCESS_MESSAGE =
            Component.text(
                    "Te enviamos al Lobby."
            );

    static final Component TRANSFER_FAILED_MESSAGE =
            Component.text(
                    "No se pudo enviarte al Lobby."
            );

    static final Component TRANSFER_TIMED_OUT_MESSAGE =
            Component.text(
                    "El traslado al Lobby tardó demasiado."
            );

    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final TransferTargetResolver targetResolver;
    private final PlayerTransferExecutor transferExecutor;
    private final Clock clock;
    private final Supplier<UUID> requestIdGenerator;

    public LobbyTransferService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            TransferTargetResolver targetResolver,
            PlayerTransferExecutor transferExecutor
    ) {
        this(
                sessionRegistry,
                transferRegistry,
                targetResolver,
                transferExecutor,
                Clock.systemUTC(),
                UUID::randomUUID
        );
    }

    LobbyTransferService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            TransferTargetResolver targetResolver,
            PlayerTransferExecutor transferExecutor,
            Clock clock,
            Supplier<UUID> requestIdGenerator
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );

        this.transferRegistry = Objects.requireNonNull(
                transferRegistry,
                "transferRegistry cannot be null"
        );

        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
        );

        this.transferExecutor = Objects.requireNonNull(
                transferExecutor,
                "transferExecutor cannot be null"
        );

        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );

        this.requestIdGenerator = Objects.requireNonNull(
                requestIdGenerator,
                "requestIdGenerator cannot be null"
        );
    }

    public void transferToLobby(Player player) {
        Player nonNullPlayer =
                Objects.requireNonNull(
                        player,
                        "player cannot be null"
                );

        UUID playerId = nonNullPlayer.getUniqueId();

        if (!sessionRegistry.isAuthenticated(playerId)) {
            nonNullPlayer.sendMessage(
                    AUTHENTICATION_REQUIRED_MESSAGE
            );
            return;
        }

        Optional<ServerConnection> currentServer =
                nonNullPlayer.getCurrentServer();

        if (currentServer.isEmpty()) {
            nonNullPlayer.sendMessage(
                    NO_CURRENT_SERVER_MESSAGE
            );
            return;
        }

        TransferTargetResolution targetResolution =
                targetResolver.resolve(BackendType.LOBBY);

        if (targetResolution.status()
                != TransferTargetResolutionStatus.RESOLVED) {
            nonNullPlayer.sendMessage(
                    LOBBY_UNAVAILABLE_MESSAGE
            );
            return;
        }

        RegisteredServer target =
                targetResolution
                        .resolvedTarget()
                        .orElseThrow();

        String sourceBackendName =
                currentServer
                        .orElseThrow()
                        .getServerInfo()
                        .getName();

        String targetBackendName =
                target
                        .getServerInfo()
                        .getName();

        if (sourceBackendName.equals(targetBackendName)) {
            nonNullPlayer.sendMessage(
                    ALREADY_IN_LOBBY_MESSAGE
            );
            return;
        }

        PendingPlayerTransfer transfer =
                new PendingPlayerTransfer(
                        requestIdGenerator.get(),
                        playerId,
                        sourceBackendName,
                        targetBackendName,
                        clock.millis()
                );

        PlayerTransferRegistrationResult registrationResult =
                transferRegistry.register(transfer);

        if (registrationResult
                != PlayerTransferRegistrationResult.REGISTERED) {
            nonNullPlayer.sendMessage(
                    TRANSFER_PENDING_MESSAGE
            );
            return;
        }

        transferExecutor
                .execute(nonNullPlayer, target)
                .whenComplete(
                        (completion, throwable) ->
                                completeTransfer(
                                        nonNullPlayer,
                                        transfer,
                                        completion,
                                        throwable
                                )
                );
    }

    private void completeTransfer(
            Player player,
            PendingPlayerTransfer transfer,
            PlayerTransferCompletion completion,
            Throwable throwable
    ) {
        Optional<PendingPlayerTransfer> removed =
                transferRegistry.removeIfMatches(transfer);

        if (removed.isEmpty()) {
            return;
        }

        PlayerTransferCompletion safeCompletion =
                throwable == null && completion != null
                        ? completion
                        : PlayerTransferCompletion.failed();

        player.sendMessage(
                messageFor(safeCompletion.status())
        );
    }

    private Component messageFor(
            TransferResultStatus status
    ) {
        return switch (status) {
            case SUCCESS -> TRANSFER_SUCCESS_MESSAGE;
            case TIMED_OUT -> TRANSFER_TIMED_OUT_MESSAGE;
            case REJECTED, FAILED -> TRANSFER_FAILED_MESSAGE;
        };
    }
}

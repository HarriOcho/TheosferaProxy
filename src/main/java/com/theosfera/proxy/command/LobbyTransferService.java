package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.PlayerTransferTargetAllocationService;
import com.theosfera.proxy.transfer.PlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
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
    private final BackendBootstrapRegistry bootstrapRegistry;
    private final TransferTargetResolver targetResolver;
    private final PlayerTransferTargetAllocationService allocationService;
    private final PlayerTransferRetryCoordinator retryCoordinator;
    private final PlayerTransferExecutor transferExecutor;
    private final Clock clock;
    private final Supplier<UUID> requestIdGenerator;

    public LobbyTransferService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendBootstrapRegistry bootstrapRegistry,
            TransferTargetResolver targetResolver,
            PlayerTransferExecutor transferExecutor
    ) {
        this(
                sessionRegistry,
                transferRegistry,
                bootstrapRegistry,
                targetResolver,
                transferExecutor,
                Clock.systemUTC(),
                UUID::randomUUID
        );
    }

    LobbyTransferService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendBootstrapRegistry bootstrapRegistry,
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

        this.bootstrapRegistry = Objects.requireNonNull(
                bootstrapRegistry,
                "bootstrapRegistry cannot be null"
        );

        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
        );

        this.allocationService =
                new PlayerTransferTargetAllocationService(
                        this.targetResolver,
                        this.transferRegistry
                );

        this.transferExecutor = Objects.requireNonNull(
                transferExecutor,
                "transferExecutor cannot be null"
        );

        this.retryCoordinator =
                new PlayerTransferRetryCoordinator(
                        this.bootstrapRegistry,
                        this.targetResolver,
                        this.transferRegistry,
                        this.allocationService,
                        this.transferExecutor
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

        String sourceBackendName =
                currentServer
                        .orElseThrow()
                        .getServerInfo()
                        .getName();

        retryCoordinator.start(
                new PlayerTransferRetryCoordinator.TransferRetryRequest(
                        requestIdGenerator.get(),
                        playerId,
                        sourceBackendName,
                        BackendType.LOBBY,
                        clock.millis(),
                        nonNullPlayer,
                        () -> nonNullPlayer.sendMessage(
                                ALREADY_IN_LOBBY_MESSAGE
                        ),
                        ignored -> nonNullPlayer.sendMessage(
                                TRANSFER_PENDING_MESSAGE
                        ),
                        ignored -> nonNullPlayer.sendMessage(
                                LOBBY_UNAVAILABLE_MESSAGE
                        ),
                        ignored -> nonNullPlayer.sendMessage(
                                LOBBY_UNAVAILABLE_MESSAGE
                        ),
                        ignored -> {
                        },
                        completion -> nonNullPlayer.sendMessage(
                                messageFor(completion.status())
                        ),
                        ignored -> {
                        }
                )
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

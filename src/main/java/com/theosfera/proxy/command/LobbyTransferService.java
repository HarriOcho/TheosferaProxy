package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.coordination.BackendCapacityReserveResult;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistrationResult;
import com.theosfera.proxy.transfer.DistributedPlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferRegistrationResult;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.theosfera.proxy.ui.TheosferaPalette.AMBER;
import static com.theosfera.proxy.ui.TheosferaPalette.GOLD;
import static com.theosfera.proxy.ui.TheosferaPalette.LIGHT_GOLD;
import static com.theosfera.proxy.ui.TheosferaPalette.SECONDARY_TEXT;

public final class LobbyTransferService {

    static final Component AUTHENTICATION_REQUIRED_MESSAGE =
            Component.text(
                    "Primero debes iniciar sesión.",
                    AMBER
            );

    static final Component NO_CURRENT_SERVER_MESSAGE =
            Component.text(
                    "No pudimos confirmar en qué servidor estás.",
                    AMBER
            ).append(Component.text(
                    " Inténtalo de nuevo.",
                    SECONDARY_TEXT
            ));

    static final Component LOBBY_UNAVAILABLE_MESSAGE =
            Component.text(
                    "El Lobby no está disponible en este momento.",
                    AMBER
            ).append(Component.text(
                    " Inténtalo de nuevo en unos segundos.",
                    SECONDARY_TEXT
            ));

    static final Component ALREADY_IN_LOBBY_MESSAGE =
            Component.text(
                    "Ya estás en el ",
                    GOLD
            ).append(Component.text(
                    "Lobby",
                    LIGHT_GOLD
            )).append(Component.text(
                    ".",
                    SECONDARY_TEXT
            ));

    static final Component TRANSFER_PENDING_MESSAGE =
            Component.text(
                    "Ya estamos procesando tu traslado.",
                    GOLD
            ).append(Component.text(
                    " Espera un momento.",
                    SECONDARY_TEXT
            ));

    static final Component TRANSFER_SUCCESS_MESSAGE =
            Component.text(
                    "Has llegado al ",
                    GOLD
            ).append(Component.text(
                    "Lobby",
                    LIGHT_GOLD
            )).append(Component.text(
                    ".",
                    SECONDARY_TEXT
            ));

    static final Component TRANSFER_FAILED_MESSAGE =
            Component.text(
                    "No pudimos llevarte al Lobby.",
                    AMBER
            ).append(Component.text(
                    " Inténtalo de nuevo.",
                    SECONDARY_TEXT
            ));

    static final Component TRANSFER_TIMED_OUT_MESSAGE =
            Component.text(
                    "El traslado al Lobby está tardando demasiado.",
                    AMBER
            ).append(Component.text(
                    " Inténtalo de nuevo.",
                    SECONDARY_TEXT
            ));

    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final DistributedPlayerTransferRetryCoordinator retryCoordinator;
    private final Clock clock;
    private final Supplier<UUID> requestIdGenerator;

    public LobbyTransferService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            DistributedPlayerTransferRetryCoordinator retryCoordinator
    ) {
        this(
                sessionRegistry,
                retryCoordinator,
                Clock.systemUTC(),
                UUID::randomUUID
        );
    }

    LobbyTransferService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            DistributedPlayerTransferRetryCoordinator retryCoordinator,
            Clock clock,
            Supplier<UUID> requestIdGenerator
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
        this.retryCoordinator = Objects.requireNonNull(
                retryCoordinator,
                "retryCoordinator cannot be null"
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
        Player nonNullPlayer = Objects.requireNonNull(
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

        String sourceBackendName = currentServer
                .orElseThrow()
                .getServerInfo()
                .getName();

        retryCoordinator.start(
                new DistributedPlayerTransferRetryCoordinator
                        .TransferRetryRequest(
                        requestIdGenerator.get(),
                        playerId,
                        sourceBackendName,
                        BackendType.LOBBY,
                        clock.millis(),
                        nonNullPlayer,
                        () -> nonNullPlayer.sendMessage(
                                ALREADY_IN_LOBBY_MESSAGE
                        ),
                        result -> handleRegistrationRejected(
                                nonNullPlayer,
                                result
                        ),
                        resolution -> handleUnavailable(
                                nonNullPlayer,
                                resolution
                        ),
                        status -> handleCapacityRejected(
                                nonNullPlayer,
                                status
                        ),
                        result -> handleBootstrapRejected(
                                nonNullPlayer,
                                result
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

    private void handleRegistrationRejected(
            Player player,
            PlayerTransferRegistrationResult result
    ) {
        switch (result) {
            case ALREADY_REGISTERED, PLAYER_BUSY, REQUEST_ID_CONFLICT ->
                    player.sendMessage(TRANSFER_PENDING_MESSAGE);
            case REGISTERED -> throw new IllegalStateException(
                    "Registered transfer cannot be rejected"
            );
        }
    }

    private void handleUnavailable(
            Player player,
            TransferTargetResolution resolution
    ) {
        switch (resolution.status()) {
            case NOT_CONFIGURED, NOT_AUTHENTICATED, NO_CAPACITY ->
                    player.sendMessage(LOBBY_UNAVAILABLE_MESSAGE);
            case RESOLVED, BOOTSTRAP_REQUIRED ->
                    throw new IllegalStateException(
                            "Resolved Lobby target cannot be rejected as unavailable"
                    );
        }
    }

    private void handleCapacityRejected(
            Player player,
            BackendCapacityReserveResult.Status status
    ) {
        switch (status) {
            case NO_CAPACITY,
                    REQUEST_ID_CONFLICT,
                    SESSION_NOT_FOUND,
                    NOT_SESSION_OWNER,
                    OCCUPANCY_UNAVAILABLE,
                    COORDINATION_UNAVAILABLE ->
                    player.sendMessage(LOBBY_UNAVAILABLE_MESSAGE);
            case RESERVED, ALREADY_RESERVED ->
                    throw new IllegalStateException(
                            "Successful capacity reservation cannot be rejected"
                    );
        }
    }

    private void handleBootstrapRejected(
            Player player,
            BackendBootstrapRegistrationResult result
    ) {
        switch (result) {
            case TARGET_BUSY, REQUEST_ID_CONFLICT, ALREADY_RESERVED ->
                    player.sendMessage(LOBBY_UNAVAILABLE_MESSAGE);
            case RESERVED -> throw new IllegalStateException(
                    "Reserved bootstrap cannot be rejected"
            );
        }
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

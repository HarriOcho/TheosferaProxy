package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.backend.BackendIdentityProvider;
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
import java.util.Set;
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

    static final Component SWITCH_REQUIRES_LOBBY_MESSAGE =
            Component.text(
                    "Debes estar en el Lobby para cambiar de instancia.",
                    AMBER
            ).append(Component.text(
                    " Usa /lobby para regresar al Lobby.",
                    SECONDARY_TEXT
            ));

    static final Component SWITCH_UNAVAILABLE_MESSAGE =
            Component.text(
                    "No hay otro Lobby disponible en este momento.",
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

    static final Component SWITCH_SUCCESS_MESSAGE =
            Component.text(
                    "Has cambiado a otro ",
                    GOLD
            ).append(Component.text(
                    "Lobby",
                    LIGHT_GOLD
            )).append(Component.text(
                    ".",
                    SECONDARY_TEXT
            ));

    static final Component SWITCH_FAILED_MESSAGE =
            Component.text(
                    "No pudimos cambiarte de Lobby.",
                    AMBER
            ).append(Component.text(
                    " Inténtalo de nuevo.",
                    SECONDARY_TEXT
            ));

    static final Component SWITCH_TIMED_OUT_MESSAGE =
            Component.text(
                    "El cambio de Lobby está tardando demasiado.",
                    AMBER
            ).append(Component.text(
                    " Inténtalo de nuevo.",
                    SECONDARY_TEXT
            ));

    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final BackendIdentityProvider identityProvider;
    private final DistributedPlayerTransferRetryCoordinator retryCoordinator;
    private final Clock clock;
    private final Supplier<UUID> requestIdGenerator;

    public LobbyTransferService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            BackendIdentityProvider identityProvider,
            DistributedPlayerTransferRetryCoordinator retryCoordinator
    ) {
        this(
                sessionRegistry,
                identityProvider,
                retryCoordinator,
                Clock.systemUTC(),
                UUID::randomUUID
        );
    }

    LobbyTransferService(
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            BackendIdentityProvider identityProvider,
            DistributedPlayerTransferRetryCoordinator retryCoordinator,
            Clock clock,
            Supplier<UUID> requestIdGenerator
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
        this.identityProvider = Objects.requireNonNull(
                identityProvider,
                "identityProvider cannot be null"
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
        Optional<TransferSource> source = prepareSource(nonNullPlayer);
        if (source.isEmpty()) {
            return;
        }

        TransferSource preparedSource = source.orElseThrow();
        retryCoordinator.start(
                requestFor(
                        nonNullPlayer,
                        preparedSource,
                        LobbyTransferMode.RETURN_TO_LOBBY
                )
        );
    }

    public void switchLobbyInstance(Player player) {
        Player nonNullPlayer = Objects.requireNonNull(
                player,
                "player cannot be null"
        );
        Optional<TransferSource> source = prepareSource(nonNullPlayer);
        if (source.isEmpty()) {
            return;
        }

        TransferSource preparedSource = source.orElseThrow();
        boolean currentBackendIsLobby = identityProvider
                .find(preparedSource.backendName())
                .map(identity -> identity.backendType() == BackendType.LOBBY)
                .orElse(false);

        if (!currentBackendIsLobby) {
            nonNullPlayer.sendMessage(SWITCH_REQUIRES_LOBBY_MESSAGE);
            return;
        }

        retryCoordinator.start(
                requestFor(
                        nonNullPlayer,
                        preparedSource,
                        LobbyTransferMode.SWITCH_INSTANCE
                ),
                Set.of(preparedSource.backendName())
        );
    }

    private Optional<TransferSource> prepareSource(Player player) {
        UUID playerId = player.getUniqueId();

        if (!sessionRegistry.isAuthenticated(playerId)) {
            player.sendMessage(AUTHENTICATION_REQUIRED_MESSAGE);
            return Optional.empty();
        }

        Optional<ServerConnection> currentServer = player.getCurrentServer();
        if (currentServer.isEmpty()) {
            player.sendMessage(NO_CURRENT_SERVER_MESSAGE);
            return Optional.empty();
        }

        String sourceBackendName = currentServer
                .orElseThrow()
                .getServerInfo()
                .getName();

        return Optional.of(
                new TransferSource(playerId, sourceBackendName)
        );
    }

    private DistributedPlayerTransferRetryCoordinator.TransferRetryRequest
    requestFor(
            Player player,
            TransferSource source,
            LobbyTransferMode mode
    ) {
        return new DistributedPlayerTransferRetryCoordinator.TransferRetryRequest(
                requestIdGenerator.get(),
                source.playerId(),
                source.backendName(),
                BackendType.LOBBY,
                clock.millis(),
                player,
                () -> player.sendMessage(sameTargetMessageFor(mode)),
                result -> handleRegistrationRejected(player, result),
                resolution -> handleUnavailable(player, resolution, mode),
                status -> handleCapacityRejected(player, status, mode),
                result -> handleBootstrapRejected(player, result, mode),
                ignored -> {
                },
                completion -> player.sendMessage(
                        messageFor(completion.status(), mode)
                ),
                ignored -> {
                }
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
            TransferTargetResolution resolution,
            LobbyTransferMode mode
    ) {
        switch (resolution.status()) {
            case NOT_CONFIGURED, NOT_AUTHENTICATED, NO_CAPACITY ->
                    player.sendMessage(unavailableMessageFor(mode));
            case RESOLVED, BOOTSTRAP_REQUIRED ->
                    throw new IllegalStateException(
                            "Resolved Lobby target cannot be rejected as unavailable"
                    );
        }
    }

    private void handleCapacityRejected(
            Player player,
            BackendCapacityReserveResult.Status status,
            LobbyTransferMode mode
    ) {
        switch (status) {
            case NO_CAPACITY,
                    REQUEST_ID_CONFLICT,
                    SESSION_NOT_FOUND,
                    NOT_SESSION_OWNER,
                    OCCUPANCY_UNAVAILABLE,
                    COORDINATION_UNAVAILABLE ->
                    player.sendMessage(unavailableMessageFor(mode));
            case RESERVED, ALREADY_RESERVED ->
                    throw new IllegalStateException(
                            "Successful capacity reservation cannot be rejected"
                    );
        }
    }

    private void handleBootstrapRejected(
            Player player,
            BackendBootstrapRegistrationResult result,
            LobbyTransferMode mode
    ) {
        switch (result) {
            case TARGET_BUSY, REQUEST_ID_CONFLICT, ALREADY_RESERVED ->
                    player.sendMessage(unavailableMessageFor(mode));
            case RESERVED -> throw new IllegalStateException(
                    "Reserved bootstrap cannot be rejected"
            );
        }
    }

    private Component sameTargetMessageFor(LobbyTransferMode mode) {
        return mode == LobbyTransferMode.SWITCH_INSTANCE
                ? SWITCH_UNAVAILABLE_MESSAGE
                : ALREADY_IN_LOBBY_MESSAGE;
    }

    private Component unavailableMessageFor(LobbyTransferMode mode) {
        return mode == LobbyTransferMode.SWITCH_INSTANCE
                ? SWITCH_UNAVAILABLE_MESSAGE
                : LOBBY_UNAVAILABLE_MESSAGE;
    }

    private Component messageFor(
            TransferResultStatus status,
            LobbyTransferMode mode
    ) {
        if (mode == LobbyTransferMode.SWITCH_INSTANCE) {
            return switch (status) {
                case SUCCESS -> SWITCH_SUCCESS_MESSAGE;
                case TIMED_OUT -> SWITCH_TIMED_OUT_MESSAGE;
                case REJECTED, FAILED -> SWITCH_FAILED_MESSAGE;
            };
        }

        return switch (status) {
            case SUCCESS -> TRANSFER_SUCCESS_MESSAGE;
            case TIMED_OUT -> TRANSFER_TIMED_OUT_MESSAGE;
            case REJECTED, FAILED -> TRANSFER_FAILED_MESSAGE;
        };
    }

    private enum LobbyTransferMode {
        RETURN_TO_LOBBY,
        SWITCH_INSTANCE
    }

    private record TransferSource(
            UUID playerId,
            String backendName
    ) {
        private TransferSource {
            Objects.requireNonNull(playerId, "playerId cannot be null");
            Objects.requireNonNull(backendName, "backendName cannot be null");
        }
    }
}

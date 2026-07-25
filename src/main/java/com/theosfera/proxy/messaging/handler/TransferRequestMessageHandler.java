package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.protocol.message.payload.TransferRequestPayload;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.backend.BackendIdentity;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistrationResult;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.PlayerTransferRegistrationResult;
import com.theosfera.proxy.transfer.PlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.PlayerTransferTargetAllocationService;
import com.theosfera.proxy.transfer.TransferResultSender;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TransferRequestMessageHandler
        implements ProtocolMessageHandler {

    private final ProxyServer proxyServer;
    private final BackendIdentityRegistry identityRegistry;
    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final PlayerServerPresenceRegistry presenceRegistry;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final BackendBootstrapRegistry bootstrapRegistry;
    private final TransferTargetResolver targetResolver;
    private final PlayerTransferTargetAllocationService allocationService;
    private final PlayerTransferRetryCoordinator retryCoordinator;
    private final PlayerTransferExecutor transferExecutor;
    private final TransferResultSender resultSender;
    private final Logger logger;
    private final Clock clock;

    public TransferRequestMessageHandler(
            ProxyServer proxyServer,
            BackendIdentityRegistry identityRegistry,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendBootstrapRegistry bootstrapRegistry,
            TransferTargetResolver targetResolver,
            PlayerTransferExecutor transferExecutor,
            TransferResultSender resultSender,
            Logger logger
    ) {
        this(
                proxyServer,
                identityRegistry,
                sessionRegistry,
                presenceRegistry,
                transferRegistry,
                bootstrapRegistry,
                targetResolver,
                transferExecutor,
                resultSender,
                logger,
                Clock.systemUTC()
        );
    }

    TransferRequestMessageHandler(
            ProxyServer proxyServer,
            BackendIdentityRegistry identityRegistry,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            BackendBootstrapRegistry bootstrapRegistry,
            TransferTargetResolver targetResolver,
            PlayerTransferExecutor transferExecutor,
            TransferResultSender resultSender,
            Logger logger,
            Clock clock
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );

        this.identityRegistry = Objects.requireNonNull(
                identityRegistry,
                "identityRegistry cannot be null"
        );

        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );

        this.presenceRegistry = Objects.requireNonNull(
                presenceRegistry,
                "presenceRegistry cannot be null"
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

        this.resultSender = Objects.requireNonNull(
                resultSender,
                "resultSender cannot be null"
        );

        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );

        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
    }

    @Override
    public String messageType() {
        return ProtocolMessageType.TRANSFER_REQUEST;
    }

    @Override
    public void handle(ProtocolMessageContext context) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        TransferRequestPayload payload =
                requireTransferPayload(
                        context.envelope()
                );

        UUID playerId = payload.playerId();
        String sourceBackendName = context.serverName();

        Optional<BackendIdentity> sourceIdentity =
                identityRegistry.find(sourceBackendName);

        if (sourceIdentity.isEmpty()) {
            reject(
                    context,
                    playerId,
                    "Source backend is not authenticated"
            );
            return;
        }

        BackendType sourceBackendType =
                sourceIdentity.orElseThrow().backendType();

        if (!isTransferAllowed(
                sourceBackendType,
                payload.targetBackendType()
        )) {
            reject(
                    context,
                    playerId,
                    "Transfer is not allowed for source and target backend types"
            );
            return;
        }

        if (!playerId.equals(
                context.source()
                        .getPlayer()
                        .getUniqueId()
        )) {
            reject(
                    context,
                    playerId,
                    "Transfer source does not match player"
            );
            return;
        }

        if (!sessionRegistry.isAuthenticated(playerId)) {
            reject(
                    context,
                    playerId,
                    "Player is not authenticated"
            );
            return;
        }

        if (sourceBackendType != BackendType.AUTH) {
            Optional<PlayerServerPresence> presence =
                    presenceRegistry.find(playerId);

            if (presence.isEmpty()
                    || !presence.orElseThrow()
                    .backendName()
                    .equals(sourceBackendName)) {
                reject(
                        context,
                        playerId,
                        "Player presence does not match source backend"
                );
                return;
            }
        }

        Optional<Player> onlinePlayer =
                proxyServer.getPlayer(playerId);

        if (onlinePlayer.isEmpty()
                || !isConnectedToSource(
                onlinePlayer.orElseThrow(),
                sourceBackendName
        )) {
            reject(
                    context,
                    playerId,
                    "Player connection does not match source backend"
            );
            return;
        }

        long createdAt = clock.millis();
        Player player = onlinePlayer.orElseThrow();

        retryCoordinator.start(
                new PlayerTransferRetryCoordinator.TransferRetryRequest(
                        context.envelope().requestId(),
                        playerId,
                        sourceBackendName,
                        payload.targetBackendType(),
                        createdAt,
                        player,
                        () -> reject(
                                context,
                                playerId,
                                "Player is already connected to target backend"
                        ),
                        result -> rejectRegistration(
                                context,
                                playerId,
                                result
                        ),
                        resolution -> rejectUnavailable(
                                context,
                                playerId,
                                resolution
                        ),
                        result -> rejectBootstrapRegistration(
                                context,
                                playerId,
                                result
                        ),
                        reservation -> logger.info(
                                "Bootstrap reservado para {} "
                                        + "(requestId: {}, playerId: {}).",
                                reservation.targetBackendName(),
                                reservation.requestId(),
                                reservation.playerId()
                        ),
                        completion -> completeTransfer(
                                context,
                                playerId,
                                sourceBackendName,
                                completion
                        ),
                        transfer -> logger.warn(
                                "Resultado tardio de transferencia ignorado "
                                        + "(requestId: {}, playerId: {}).",
                                transfer.requestId(),
                                transfer.playerId()
                        )
                )
        );
    }

    private void completeTransfer(
            ProtocolMessageContext context,
            UUID playerId,
            String sourceBackendName,
            PlayerTransferCompletion completion
    ) {
        if (completion.status()
                == TransferResultStatus.SUCCESS) {
            presenceRegistry.removeIfBackend(
                    playerId,
                    sourceBackendName
            );
        }

        resultSender.send(
                context,
                playerId,
                completion.status(),
                completion.message()
        );
    }

    private boolean isTransferAllowed(
            BackendType sourceBackendType,
            BackendType targetBackendType
    ) {
        if (targetBackendType == BackendType.AUTH) {
            return false;
        }

        return switch (sourceBackendType) {
            case AUTH ->
                    targetBackendType == BackendType.LOBBY;
            case LOBBY, SKYBLOCK -> true;
        };
    }

    private boolean isConnectedToSource(
            Player player,
            String sourceBackendName
    ) {
        return player
                .getCurrentServer()
                .map(connection ->
                        connection
                                .getServerInfo()
                                .getName()
                                .equals(sourceBackendName)
                )
                .orElse(false);
    }

    private void rejectRegistration(
            ProtocolMessageContext context,
            UUID playerId,
            PlayerTransferRegistrationResult result
    ) {
        String message = switch (result) {
            case ALREADY_REGISTERED ->
                    "Transfer request is already pending";
            case PLAYER_BUSY ->
                    "Player already has a pending transfer";
            case REQUEST_ID_CONFLICT ->
                    "Transfer request identifier conflict";
            case REGISTERED ->
                    throw new IllegalStateException(
                            "Registered transfer cannot be rejected"
                    );
        };

        reject(context, playerId, message);
    }

    private void rejectUnavailable(
            ProtocolMessageContext context,
            UUID playerId,
            TransferTargetResolution resolution
    ) {
        String message = switch (resolution.status()) {
            case NOT_CONFIGURED ->
                    "Target backend is not configured";
            case NOT_AUTHENTICATED ->
                    "Target backend is not authenticated";
            case NO_CAPACITY ->
                    "Target backend has no available capacity";
            case RESOLVED, BOOTSTRAP_REQUIRED ->
                    throw new IllegalStateException(
                            "Resolved target cannot be rejected as unavailable"
                    );
        };

        reject(context, playerId, message);
    }

    private void rejectBootstrapRegistration(
            ProtocolMessageContext context,
            UUID playerId,
            BackendBootstrapRegistrationResult result
    ) {
        String message = switch (result) {
            case TARGET_BUSY ->
                    "Target backend bootstrap is already in progress";
            case REQUEST_ID_CONFLICT ->
                    "Bootstrap request identifier conflict";
            case ALREADY_RESERVED ->
                    "Bootstrap request is already pending";
            case RESERVED ->
                    throw new IllegalStateException(
                            "Reserved bootstrap cannot be rejected"
                    );
        };

        reject(context, playerId, message);
    }

    private void reject(
            ProtocolMessageContext context,
            UUID playerId,
            String message
    ) {
        resultSender.send(
                context,
                playerId,
                TransferResultStatus.REJECTED,
                message
        );
    }

    private TransferRequestPayload requireTransferPayload(
            ProtocolEnvelope<?> envelope
    ) {
        if (!(envelope.payload()
                instanceof TransferRequestPayload payload)) {
            throw new IllegalArgumentException(
                    "TRANSFER_REQUEST envelope requires "
                            + "TransferRequestPayload"
            );
        }

        return payload;
    }
}

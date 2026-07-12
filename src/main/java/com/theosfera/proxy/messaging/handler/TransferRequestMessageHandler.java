package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.TransferRequestPayload;
import com.theosfera.protocol.message.payload.TransferResultStatus;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.transfer.PendingPlayerTransfer;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferCompletion;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.PlayerTransferRegistrationResult;
import com.theosfera.proxy.transfer.TransferResultSender;
import com.theosfera.proxy.transfer.TransferTargetResolution;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TransferRequestMessageHandler
        implements ProtocolMessageHandler {

    private final ProxyServer proxyServer;
    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final PlayerServerPresenceRegistry presenceRegistry;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final TransferTargetResolver targetResolver;
    private final PlayerTransferExecutor transferExecutor;
    private final TransferResultSender resultSender;
    private final Logger logger;
    private final Clock clock;

    public TransferRequestMessageHandler(
            ProxyServer proxyServer,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
            TransferTargetResolver targetResolver,
            PlayerTransferExecutor transferExecutor,
            TransferResultSender resultSender,
            Logger logger
    ) {
        this(
                proxyServer,
                sessionRegistry,
                presenceRegistry,
                transferRegistry,
                targetResolver,
                transferExecutor,
                resultSender,
                logger,
                Clock.systemUTC()
        );
    }

    TransferRequestMessageHandler(
            ProxyServer proxyServer,
            AuthenticatedPlayerSessionRegistry sessionRegistry,
            PlayerServerPresenceRegistry presenceRegistry,
            PendingPlayerTransferRegistry transferRegistry,
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

        this.targetResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
        );

        this.transferExecutor = Objects.requireNonNull(
                transferExecutor,
                "transferExecutor cannot be null"
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

        TransferTargetResolution targetResolution =
                targetResolver.resolve(
                        payload.targetBackendType()
                );

        switch (targetResolution.status()) {
            case NOT_CONFIGURED -> {
                reject(
                        context,
                        playerId,
                        "Target backend is not configured"
                );
                return;
            }
            case NOT_AUTHENTICATED -> {
                reject(
                        context,
                        playerId,
                        "Target backend is not authenticated"
                );
                return;
            }
            case RESOLVED -> {
                // Continúa debajo.
            }
        }

        RegisteredServer target =
                targetResolution
                        .resolvedTarget()
                        .orElseThrow();

        String targetBackendName =
                target.getServerInfo().getName();

        if (sourceBackendName.equals(targetBackendName)) {
            reject(
                    context,
                    playerId,
                    "Player is already connected to target backend"
            );
            return;
        }

        PendingPlayerTransfer transfer =
                new PendingPlayerTransfer(
                        context.envelope().requestId(),
                        playerId,
                        sourceBackendName,
                        targetBackendName,
                        clock.millis()
                );

        PlayerTransferRegistrationResult registrationResult =
                transferRegistry.register(transfer);

        if (registrationResult
                != PlayerTransferRegistrationResult.REGISTERED) {
            rejectRegistration(
                    context,
                    playerId,
                    registrationResult
            );
            return;
        }

        Player player = onlinePlayer.orElseThrow();

        transferExecutor
                .execute(player, target)
                .whenComplete(
                        (completion, throwable) ->
                                completeTransfer(
                                        context,
                                        transfer,
                                        completion,
                                        throwable
                                )
                );
    }

    private void completeTransfer(
            ProtocolMessageContext context,
            PendingPlayerTransfer transfer,
            PlayerTransferCompletion completion,
            Throwable throwable
    ) {
        Optional<PendingPlayerTransfer> removed =
                transferRegistry.remove(
                        transfer.requestId()
                );

        if (removed.isEmpty()
                || !removed.orElseThrow().equals(transfer)) {
            logger.warn(
                    "Resultado tardío de transferencia ignorado "
                            + "(requestId: {}, playerId: {}).",
                    transfer.requestId(),
                    transfer.playerId()
            );
            return;
        }

        PlayerTransferCompletion safeCompletion =
                throwable == null && completion != null
                        ? completion
                        : PlayerTransferCompletion.failed();

        if (safeCompletion.status()
                == TransferResultStatus.SUCCESS) {
            presenceRegistry.removeIfBackend(
                    transfer.playerId(),
                    transfer.sourceBackendName()
            );
        }

        resultSender.send(
                context,
                transfer.playerId(),
                safeCompletion.status(),
                safeCompletion.message()
        );
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
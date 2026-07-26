package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PlayerAuthenticatedPayload;
import com.theosfera.proxy.coordination.PlayerSessionAcquireResult;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionLease;
import com.theosfera.proxy.coordination.PlayerSessionLeaseRequest;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.messaging.ProtocolMessageHandler;
import com.theosfera.proxy.session.AuthenticatedPlayerSession;
import com.theosfera.proxy.session.PlayerAuthenticationAckSender;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingResult;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class PlayerAuthenticatedMessageHandler
        implements ProtocolMessageHandler {

    private final PlayerSessionCoordinator sessionCoordinator;
    private final PlayerSessionLeaseBindingRegistry
            leaseBindingRegistry;
    private final ProxyInstanceIdentity proxyIdentity;
    private final PlayerAuthenticationAckSender
            acknowledgementSender;
    private final Logger logger;

    public PlayerAuthenticatedMessageHandler(
            PlayerSessionCoordinator sessionCoordinator,
            PlayerSessionLeaseBindingRegistry
                    leaseBindingRegistry,
            ProxyInstanceIdentity proxyIdentity,
            PlayerAuthenticationAckSender acknowledgementSender,
            Logger logger
    ) {
        this.sessionCoordinator = Objects.requireNonNull(
                sessionCoordinator,
                "sessionCoordinator cannot be null"
        );

        this.leaseBindingRegistry =
                Objects.requireNonNull(
                        leaseBindingRegistry,
                        "leaseBindingRegistry cannot be null"
                );

        this.proxyIdentity = Objects.requireNonNull(
                proxyIdentity,
                "proxyIdentity cannot be null"
        );

        this.acknowledgementSender =
                Objects.requireNonNull(
                        acknowledgementSender,
                        "acknowledgementSender cannot be null"
                );

        this.logger = Objects.requireNonNull(
                logger,
                "logger cannot be null"
        );
    }

    @Override
    public String messageType() {
        return ProtocolMessageType.PLAYER_AUTHENTICATED;
    }

    @Override
    public void handle(ProtocolMessageContext context) {
        Objects.requireNonNull(
                context,
                "context cannot be null"
        );

        PlayerAuthenticatedPayload payload =
                requireAuthenticatedPayload(
                        context.envelope()
                );

        Player carrier = context.source().getPlayer();

        if (!carrier.getUniqueId().equals(
                payload.playerId()
        )) {
            rejectIdentityMismatch(
                    context,
                    payload,
                    carrier
            );
            return;
        }

        if (!carrier.getUsername().equals(
                payload.playerName()
        )) {
            rejectIdentityMismatch(
                    context,
                    payload,
                    carrier
            );
            return;
        }

        AuthenticatedPlayerSession session =
                new AuthenticatedPlayerSession(
                        payload.playerId(),
                        payload.playerName(),
                        payload.authenticatedAt()
                );

        UUID acquisitionId =
                context.envelope().requestId();

        long attemptId =
                leaseBindingRegistry.begin(
                        carrier,
                        acquisitionId
                );

        startAcquisition(
                context,
                carrier,
                session,
                acquisitionId,
                attemptId
        );
    }

    private void startAcquisition(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId
    ) {
        CompletionStage<PlayerSessionAcquireResult>
                acquisitionStage;

        try {
            acquisitionStage = Objects.requireNonNull(
                    sessionCoordinator.acquire(
                            new PlayerSessionLeaseRequest(
                                    session,
                                    proxyIdentity
                            )
                    ),
                    "sessionCoordinator.acquire returned null"
            );
        } catch (RuntimeException exception) {
            boolean claimed =
                    leaseBindingRegistry
                            .claimAcquisitionResult(
                                    carrier,
                                    acquisitionId,
                                    attemptId
                            );

            if (!claimed) {
                return;
            }

            rejectCoordinationFailure(
                    context,
                    carrier,
                    session,
                    acquisitionId,
                    exception
            );
            return;
        }

        acquisitionStage.whenComplete(
                (result, failure) -> {
                    boolean claimed =
                            leaseBindingRegistry
                                    .claimAcquisitionResult(
                                            carrier,
                                            acquisitionId,
                                            attemptId
                                    );

                    if (!claimed) {
                        releaseUnclaimedSuccessfulAcquisition(
                                result
                        );
                        return;
                    }

                    if (failure != null) {
                        rejectCoordinationFailure(
                                context,
                                carrier,
                                session,
                                acquisitionId,
                                failure
                        );
                        return;
                    }

                    if (result == null) {
                        rejectCoordinationFailure(
                                context,
                                carrier,
                                session,
                                acquisitionId,
                                new IllegalStateException(
                                        "Session acquisition "
                                                + "returned null"
                                )
                        );
                        return;
                    }

                    try {
                        handleAcquisitionResult(
                                context,
                                carrier,
                                session,
                                acquisitionId,
                                attemptId,
                                result
                        );
                    } catch (RuntimeException exception) {
                        rejectCoordinationFailure(
                                context,
                                carrier,
                                session,
                                acquisitionId,
                                exception
                        );
                    }
                }
        );
    }
    private void releaseUnclaimedSuccessfulAcquisition(
            PlayerSessionAcquireResult result
    ) {
        if (result == null) {
            return;
        }

        boolean successful =
                result.status()
                        == PlayerSessionAcquireResult.Status.ACQUIRED
                        || result.status()
                        == PlayerSessionAcquireResult.Status.ALREADY_OWNED;

        if (!successful) {
            return;
        }

        PlayerSessionLease lease =
                result.lease().orElseThrow();

        if (!lease.owner().equals(proxyIdentity)) {
            return;
        }

        releaseUnboundLease(
                lease,
                PlayerSessionLeaseBindingResult.STALE
        );
    }
    private void handleAcquisitionResult(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId,
            PlayerSessionAcquireResult result
    ) {
        switch (result.status()) {
            case ACQUIRED, ALREADY_OWNED ->
                    handleSuccessfulAcquisition(
                            context,
                            carrier,
                            session,
                            acquisitionId,
                            attemptId,
                            result
                    );

            case OWNED_BY_OTHER_PROXY ->
                    rejectAcquisition(
                            context,
                            carrier,
                            session,
                            acquisitionId,
                            "Player session owned by another proxy",
                            "La sesión autenticada de {} "
                                    + "recibida desde {} ya pertenece "
                                    + "a otra instancia Proxy."
                    );

            case CONFLICT -> {
                boolean waitingForRelease =
                        tryWaitForPendingRelease(
                                context,
                                carrier,
                                session,
                                acquisitionId,
                                attemptId
                        );

                if (!waitingForRelease) {
                    rejectAcquisition(
                            context,
                            carrier,
                            session,
                            acquisitionId,
                            "Player session conflict",
                            "Conflicto de sesión autenticada "
                                    + "para {} recibido desde {}."
                    );
                }
            }

            case COORDINATION_UNAVAILABLE ->
                    rejectAcquisition(
                            context,
                            carrier,
                            session,
                            acquisitionId,
                            "Player session coordination unavailable",
                            "Coordinación de sesión no disponible "
                                    + "para {} desde {}."
                    );
        }
    }

    private void handleSuccessfulAcquisition(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId,
            PlayerSessionAcquireResult result
    ) {
        PlayerSessionLease lease =
                result.lease().orElseThrow();

        boolean matchesRequest =
                lease.session().equals(session)
                        && lease.owner().equals(proxyIdentity);

        if (!matchesRequest) {
            if (lease.owner().equals(proxyIdentity)) {
                releaseUnboundLease(
                        lease,
                        PlayerSessionLeaseBindingResult.CONFLICT
                );
            }

            rejectCoordinationFailure(
                    context,
                    carrier,
                    session,
                    acquisitionId,
                    new IllegalStateException(
                            "Session coordinator returned a lease "
                                    + "that does not match the request"
                    )
            );
            return;
        }

        PlayerSessionLeaseBindingResult bindingResult =
                leaseBindingRegistry.bind(
                        carrier,
                        acquisitionId,
                        lease
                );

        switch (bindingResult) {
            case BOUND, ALREADY_BOUND, REPLACED ->
                    acknowledgeSuccessfulAcquisition(
                            context,
                            session,
                            result.status()
                    );

            case DISCONNECTED ->
                    releaseUnboundLease(
                            lease,
                            bindingResult
                    );

            case RELEASE_PENDING ->
                    tryWaitForPendingRelease(
                                context,
                                carrier,
                                session,
                                acquisitionId,
                                attemptId
                        );

            case STALE -> {
                releaseUnboundLease(
                        lease,
                        bindingResult
                );

                logger.debug(
                            "Resultado obsoleto de adquisición "
                                    + "ignorado para {} desde {} "
                                    + "(token {}).",
                            session.playerId(),
                            context.serverName(),
                            lease.fencingToken()
                    );
            }

            case CONFLICT -> {
                releaseUnboundLease(
                        lease,
                        bindingResult
                );

                acknowledgementSender.send(
                        context,
                        session.playerId(),
                        false,
                        "Player session binding conflict"
                );

                logger.error(
                        "Conflicto interno al vincular el lease "
                                + "de sesión para {} desde {}.",
                        session.playerId(),
                        context.serverName()
                );
            }
        }
    }

    private boolean tryWaitForPendingRelease(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId
    ) {
        Optional<CompletionStage<Boolean>> completion =
                leaseBindingRegistry
                        .awaitPendingRelease(
                                carrier,
                                acquisitionId,
                                proxyIdentity
                        );

        if (completion.isEmpty()) {
            return false;
        }

        waitForPendingRelease(
                context,
                carrier,
                session,
                acquisitionId,
                attemptId,
                completion.orElseThrow()
        );

        return true;
    }

    private void waitForPendingRelease(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId,
            CompletionStage<Boolean> completion
    ) {
        CompletionStage<Boolean> nonNullCompletion =
                Objects.requireNonNull(
                        completion,
                        "completion cannot be null"
                );

        nonNullCompletion.whenComplete(
                (released, failure) -> {
                    if (failure != null
                            || !Boolean.TRUE.equals(released)) {
                        boolean claimed =
                                leaseBindingRegistry
                                        .claimReleaseCompletion(
                                                carrier,
                                                acquisitionId,
                                                nonNullCompletion
                                        );

                        if (!claimed) {
                            return;
                        }

                        Throwable cause =
                                failure != null
                                        ? failure
                                        : new IllegalStateException(
                                        "Pending lease release failed"
                                );

                        rejectCoordinationFailure(
                                context,
                                carrier,
                                session,
                                acquisitionId,
                                cause
                        );
                        return;
                    }

                    OptionalLong retryAttempt =
                            leaseBindingRegistry
                                    .claimReleaseCompletionAndBeginRetry(
                                            carrier,
                                            acquisitionId,
                                            attemptId,
                                            nonNullCompletion
                                    );

                    if (retryAttempt.isEmpty()) {
                        return;
                    }

                    startAcquisition(
                            context,
                            carrier,
                            session,
                            acquisitionId,
                            retryAttempt.getAsLong()
                    );
                }
        );
    }

    private void acknowledgeSuccessfulAcquisition(
            ProtocolMessageContext context,
            AuthenticatedPlayerSession session,
            PlayerSessionAcquireResult.Status status
    ) {
        if (status
                == PlayerSessionAcquireResult.Status.ACQUIRED) {
            acknowledgementSender.send(
                    context,
                    session.playerId(),
                    true,
                    "Player session registered"
            );

            logger.info(
                    "Sesión autenticada registrada para {} "
                            + "({}) desde {}.",
                    session.playerName(),
                    session.playerId(),
                    context.serverName()
            );

            return;
        }

        acknowledgementSender.send(
                context,
                session.playerId(),
                true,
                "Player session already registered"
        );

        logger.debug(
                "Sesión autenticada ya registrada para {} "
                        + "({}).",
                session.playerName(),
                session.playerId()
        );
    }

    private void rejectAcquisition(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            String acknowledgementMessage,
            String logMessage
    ) {
        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                leaseBindingRegistry.cancel(
                        carrier,
                        acquisitionId
                );

        cancellation.leaseToRelease().ifPresent(
                lease -> releaseUnboundLease(
                        lease,
                        PlayerSessionLeaseBindingResult.DISCONNECTED
                )
        );

        if (!cancellation.shouldRespond()) {
            logger.debug(
                    "Resultado tardío de adquisición ignorado "
                            + "para {} desde {}.",
                    session.playerId(),
                    context.serverName()
            );
            return;
        }

        acknowledgementSender.send(
                context,
                session.playerId(),
                false,
                acknowledgementMessage
        );

        logger.warn(
                logMessage,
                session.playerId(),
                context.serverName()
        );
    }

    private void rejectCoordinationFailure(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            Throwable failure
    ) {
        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                leaseBindingRegistry.cancel(
                        carrier,
                        acquisitionId
                );

        cancellation.leaseToRelease().ifPresent(
                lease -> releaseUnboundLease(
                        lease,
                        PlayerSessionLeaseBindingResult.DISCONNECTED
                )
        );

        if (!cancellation.shouldRespond()) {
            logger.debug(
                    "Fallo tardío de coordinación ignorado "
                            + "para {} desde {}.",
                    session.playerId(),
                    context.serverName()
            );
            return;
        }

        acknowledgementSender.send(
                context,
                session.playerId(),
                false,
                "Player session coordination unavailable"
        );

        logger.error(
                "No se pudo coordinar la sesión autenticada "
                        + "de {} desde {}.",
                session.playerId(),
                context.serverName(),
                failure
        );
    }

    private void releaseUnboundLease(
            PlayerSessionLease lease,
            PlayerSessionLeaseBindingResult bindingResult
    ) {
        boolean releaseReserved =
                leaseBindingRegistry
                        .reserveReleaseIfUnbound(lease);

        if (!releaseReserved) {
            logger.debug(
                    "No se liberó el lease de {} porque está "
                            + "protegido por un binding activo "
                            + "o ya tiene una liberación pendiente "
                            + "({}, token {}).",
                    lease.session().playerId(),
                    bindingResult,
                    lease.fencingToken()
            );
            return;
        }

        CompletionStage<Boolean> releaseStage;

        try {
            releaseStage = Objects.requireNonNull(
                    sessionCoordinator.releaseIfOwned(lease),
                    "sessionCoordinator.releaseIfOwned "
                            + "returned null"
            );
        } catch (RuntimeException exception) {
            leaseBindingRegistry.failRelease(
                    lease,
                    exception
            );

            logger.error(
                    "No se pudo iniciar la liberación del lease "
                            + "no vinculado de {} ({}, token {}).",
                    lease.session().playerId(),
                    bindingResult,
                    lease.fencingToken(),
                    exception
            );
            return;
        }

        releaseStage.whenComplete(
                (released, failure) -> {
                    if (failure != null) {
                        leaseBindingRegistry.failRelease(
                                lease,
                                failure
                        );

                        logger.error(
                                "Falló la liberación del lease "
                                        + "no vinculado de {} "
                                        + "({}, token {}).",
                                lease.session().playerId(),
                                bindingResult,
                                lease.fencingToken(),
                                failure
                        );
                        return;
                    }

                    boolean releaseSucceeded =
                            Boolean.TRUE.equals(released);

                    leaseBindingRegistry.completeRelease(
                            lease,
                            releaseSucceeded
                    );

                    if (!releaseSucceeded) {
                        logger.debug(
                                "El lease no vinculado de {} "
                                        + "ya no era propiedad exacta "
                                        + "de esta operación "
                                        + "({}, token {}).",
                                lease.session().playerId(),
                                bindingResult,
                                lease.fencingToken()
                        );
                    }
                }
        );
    }

    private void rejectIdentityMismatch(
            ProtocolMessageContext context,
            PlayerAuthenticatedPayload payload,
            Player carrier
    ) {
        acknowledgementSender.send(
                context,
                payload.playerId(),
                false,
                "Player identity mismatch"
        );

        logger.warn(
                "PLAYER_AUTHENTICATED rechazado desde {}: "
                        + "la identidad declarada ({}, {}) "
                        + "no coincide con el jugador portador "
                        + "({}, {}).",
                context.serverName(),
                payload.playerId(),
                payload.playerName(),
                carrier.getUniqueId(),
                carrier.getUsername()
        );
    }

    private PlayerAuthenticatedPayload
    requireAuthenticatedPayload(
            ProtocolEnvelope<?> envelope
    ) {
        if (!(envelope.payload()
                instanceof PlayerAuthenticatedPayload payload)) {
            throw new IllegalArgumentException(
                    "PLAYER_AUTHENTICATED envelope requires "
                            + "PlayerAuthenticatedPayload"
            );
        }

        return payload;
    }
}

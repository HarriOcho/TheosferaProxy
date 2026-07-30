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
import com.theosfera.proxy.session.PlayerSessionAcquisitionTimeoutScheduler;
import com.theosfera.proxy.session.PlayerSessionAcquisitionTimeoutScheduler.ScheduledAcquisitionTimeout;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry.TerminalAcknowledgement;
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
    private final PlayerSessionAcquisitionTimeoutScheduler
            acquisitionTimeoutScheduler;
    private final Logger logger;

    public PlayerAuthenticatedMessageHandler(
            PlayerSessionCoordinator sessionCoordinator,
            PlayerSessionLeaseBindingRegistry
                    leaseBindingRegistry,
            ProxyInstanceIdentity proxyIdentity,
            PlayerAuthenticationAckSender acknowledgementSender,
            PlayerSessionAcquisitionTimeoutScheduler
                    acquisitionTimeoutScheduler,
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

        this.acquisitionTimeoutScheduler =
                Objects.requireNonNull(
                        acquisitionTimeoutScheduler,
                        "acquisitionTimeoutScheduler cannot be null"
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

        PlayerSessionLeaseBindingRegistry.BeginResult
                beginResult =
                leaseBindingRegistry.beginTracked(
                        carrier,
                        acquisitionId,
                        session
                );

        beginResult.leaseToRelease().ifPresent(
                lease -> releaseUnboundLease(
                        lease,
                        PlayerSessionLeaseBindingResult.CONFLICT
                )
        );

        switch (beginResult.decision()) {
            case PENDING_REPLAY -> {
                logger.debug(
                        "Replay pendiente de PLAYER_AUTHENTICATED "
                                + "ignorado para {} desde {} "
                                + "(requestId {}).",
                        session.playerId(),
                        context.serverName(),
                        acquisitionId
                );
                return;
            }

            case COMPLETED_REPLAY -> {
                beginResult.acknowledgement().ifPresent(
                        acknowledgement ->
                                acknowledgementSender.send(
                                        context,
                                        session.playerId(),
                                        acknowledgement.successful(),
                                        acknowledgement.message()
                                )
                );

                logger.debug(
                        "Replay terminal de PLAYER_AUTHENTICATED "
                                + "respondido para {} desde {} "
                                + "(requestId {}).",
                        session.playerId(),
                        context.serverName(),
                        acquisitionId
                );
                return;
            }

            case CAPACITY_EXHAUSTED -> {
                acknowledgementSender.send(
                        context,
                        session.playerId(),
                        false,
                        "Player authentication request capacity exhausted"
                );

                logger.error(
                        "PLAYER_AUTHENTICATED rechazado para {} "
                                + "desde {}: capacidad de deduplicación "
                                + "agotada (requestId {}).",
                        session.playerId(),
                        context.serverName(),
                        acquisitionId
                );
                return;
            }

            case CONFLICT -> {
                acknowledgementSender.send(
                        context,
                        session.playerId(),
                        false,
                        "Player authentication request conflict"
                );

                logger.warn(
                        "PLAYER_AUTHENTICATED rechazado para {} "
                                + "desde {}: requestId {} reutilizado "
                                + "con otro payload.",
                        session.playerId(),
                        context.serverName(),
                        acquisitionId
                );
                return;
            }

            case PROCEED ->
                    startAcquisition(
                            context,
                            carrier,
                            session,
                            acquisitionId,
                            beginResult.attemptId()
                    );
        }
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
                    attemptId,
                    exception
            );
            return;
        }

        ScheduledAcquisitionTimeout timeout =
                scheduleAcquisitionTimeout(
                        context,
                        carrier,
                        session,
                        acquisitionId,
                        attemptId
                );

        acquisitionStage.whenComplete(
                (result, failure) -> {
                    cancelAcquisitionTimeoutSafely(timeout);

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
                                attemptId,
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
                                attemptId,
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
                                attemptId,
                                exception
                        );
                    }
                }
        );
    }

    private ScheduledAcquisitionTimeout scheduleAcquisitionTimeout(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId
    ) {
        try {
            return Objects.requireNonNull(
                    acquisitionTimeoutScheduler.schedule(
                            new PlayerSessionAcquisitionTimeoutScheduler
                                    .AcquisitionTimeoutKey(
                                    session.playerId(),
                                    acquisitionId,
                                    attemptId
                            ),
                            () -> handleAcquisitionTimeout(
                                    context,
                                    carrier,
                                    session,
                                    acquisitionId,
                                    attemptId
                            )
                    ),
                    "acquisitionTimeoutScheduler.schedule returned null"
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo programar el timeout de adquisicion "
                            + "de sesion para {} desde {} "
                            + "(requestId {}, attemptId {}).",
                    session.playerId(),
                    context.serverName(),
                    acquisitionId,
                    attemptId,
                    exception
            );

            handleAcquisitionTimeout(
                    context,
                    carrier,
                    session,
                    acquisitionId,
                    attemptId
            );

            return () -> {
            };
        }
    }

    private void cancelAcquisitionTimeoutSafely(
            ScheduledAcquisitionTimeout timeout
    ) {
        try {
            timeout.cancel();
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo cancelar el timeout de adquisicion "
                            + "de sesion.",
                    exception
            );
        }
    }

    private ScheduledAcquisitionTimeout schedulePendingReleaseTimeout(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId,
            CompletionStage<Boolean> completion,
            TerminalAcknowledgement acknowledgement
    ) {
        Runnable timeout =
                () -> handlePendingReleaseTimeout(
                        context,
                        carrier,
                        session,
                        acquisitionId,
                        attemptId,
                        completion,
                        acknowledgement
                );

        try {
            return Objects.requireNonNull(
                    acquisitionTimeoutScheduler.schedule(
                            new PlayerSessionAcquisitionTimeoutScheduler
                                    .AcquisitionTimeoutKey(
                                    session.playerId(),
                                    acquisitionId,
                                    attemptId
                            ),
                            timeout
                    ),
                    "acquisitionTimeoutScheduler.schedule returned null"
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo programar el timeout de espera "
                            + "de liberacion de sesion para {} "
                            + "desde {} (requestId {}, attemptId {}).",
                    session.playerId(),
                    context.serverName(),
                    acquisitionId,
                    attemptId,
                    exception
            );

            timeout.run();

            return () -> {
            };
        }
    }

    private void cancelPendingReleaseTimeoutSafely(
            ScheduledAcquisitionTimeout timeout
    ) {
        try {
            timeout.cancel();
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudo cancelar el timeout de espera "
                            + "de liberacion de sesion.",
                    exception
            );
        }
    }

    private void handlePendingReleaseTimeout(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId,
            CompletionStage<Boolean> completion,
            TerminalAcknowledgement acknowledgement
    ) {
        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                leaseBindingRegistry.claimPendingReleaseTimeout(
                        carrier,
                        acquisitionId,
                        attemptId,
                        session,
                        completion,
                        acknowledgement
                );

        cancellation.leaseToRelease().ifPresent(
                lease -> releaseUnboundLease(
                        lease,
                        PlayerSessionLeaseBindingResult.DISCONNECTED
                )
        );

        if (!cancellation.shouldRespond()) {
            logger.debug(
                    "Timeout obsoleto de espera de liberacion "
                            + "ignorado para {} desde {} "
                            + "(requestId {}, attemptId {}).",
                    session.playerId(),
                    context.serverName(),
                    acquisitionId,
                    attemptId
            );
            return;
        }

        acknowledgementSender.send(
                context,
                session.playerId(),
                acknowledgement.successful(),
                acknowledgement.message()
        );

        logger.warn(
                "Timeout de espera de liberacion de sesion para {} "
                        + "desde {} (requestId {}, attemptId {}).",
                session.playerId(),
                context.serverName(),
                acquisitionId,
                attemptId
        );
    }

    private void handleAcquisitionTimeout(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId
    ) {
        TerminalAcknowledgement acknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                cancellation =
                leaseBindingRegistry.claimAcquisitionTimeout(
                        carrier,
                        acquisitionId,
                        attemptId,
                        session,
                        acknowledgement
                );

        cancellation.leaseToRelease().ifPresent(
                lease -> releaseUnboundLease(
                        lease,
                        PlayerSessionLeaseBindingResult.DISCONNECTED
                )
        );

        if (!cancellation.shouldRespond()) {
            logger.debug(
                    "Timeout obsoleto de adquisicion ignorado "
                            + "para {} desde {} "
                            + "(requestId {}, attemptId {}).",
                    session.playerId(),
                    context.serverName(),
                    acquisitionId,
                    attemptId
            );
            return;
        }

        acknowledgementSender.send(
                context,
                session.playerId(),
                acknowledgement.successful(),
                acknowledgement.message()
        );

        logger.warn(
                "Timeout de adquisicion de sesion para {} "
                        + "desde {} (requestId {}, attemptId {}).",
                session.playerId(),
                context.serverName(),
                acquisitionId,
                attemptId
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
                            attemptId,
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
                            attemptId,
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
                            attemptId,
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
                    attemptId,
                    new IllegalStateException(
                            "Session coordinator returned a lease "
                                    + "that does not match the request"
                    )
            );
            return;
        }

        String successfulMessage =
                switch (result.status()) {
                    case ACQUIRED ->
                            "Player session registered";
                    case ALREADY_OWNED ->
                            "Player session already registered";
                    default ->
                            throw new IllegalArgumentException(
                                    "Unexpected successful acquisition "
                                            + "status: "
                                            + result.status()
                            );
                };

        TerminalAcknowledgement successfulAcknowledgement =
                new TerminalAcknowledgement(
                        true,
                        successfulMessage
                );

        TerminalAcknowledgement conflictAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session binding conflict"
                );

        PlayerSessionLeaseBindingResult bindingResult =
                leaseBindingRegistry.bind(
                        carrier,
                        acquisitionId,
                        attemptId,
                        session,
                        lease,
                        successfulAcknowledgement,
                        conflictAcknowledgement
                );

        switch (bindingResult) {
            case BOUND, ALREADY_BOUND, REPLACED -> {
                acknowledgementSender.send(
                            context,
                            session.playerId(),
                            successfulAcknowledgement.successful(),
                            successfulAcknowledgement.message()
                );

                if (result.status()
                        == PlayerSessionAcquireResult
                        .Status.ACQUIRED) {
                    logger.info(
                            "Sesión autenticada registrada para {} "
                                    + "({}) desde {}.",
                            session.playerName(),
                            session.playerId(),
                            context.serverName()
                    );

                    return;
                }

                logger.debug(
                        "Sesión autenticada ya registrada para {} "
                                + "({}).",
                        session.playerName(),
                        session.playerId()
                );
            }

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
                        conflictAcknowledgement.successful(),
                        conflictAcknowledgement.message()
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

        TerminalAcknowledgement timeoutAcknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        ScheduledAcquisitionTimeout pendingReleaseTimeout =
                schedulePendingReleaseTimeout(
                        context,
                        carrier,
                        session,
                        acquisitionId,
                        attemptId,
                        nonNullCompletion,
                        timeoutAcknowledgement
                );

        nonNullCompletion.whenComplete(
                (released, failure) -> {
                    cancelPendingReleaseTimeoutSafely(
                            pendingReleaseTimeout
                    );

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
                                attemptId,
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


    private void rejectAcquisition(
            ProtocolMessageContext context,
            Player carrier,
            AuthenticatedPlayerSession session,
            UUID acquisitionId,
            long attemptId,
            String acknowledgementMessage,
            String logMessage
    ) {
        TerminalAcknowledgement acknowledgement =
                new TerminalAcknowledgement(
                        false,
                        acknowledgementMessage
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                completion =
                leaseBindingRegistry.completeTerminalRequest(
                        carrier,
                        acquisitionId,
                        attemptId,
                        session,
                        acknowledgement
                );

        completion.leaseToRelease().ifPresent(
                lease -> releaseUnboundLease(
                        lease,
                        PlayerSessionLeaseBindingResult.DISCONNECTED
                )
        );

        if (!completion.shouldRespond()) {
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
                acknowledgement.successful(),
                acknowledgement.message()
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
            long attemptId,
            Throwable failure
    ) {
        TerminalAcknowledgement acknowledgement =
                new TerminalAcknowledgement(
                        false,
                        "Player session coordination unavailable"
                );

        PlayerSessionLeaseBindingRegistry.Cancellation
                completion =
                leaseBindingRegistry.completeTerminalRequest(
                        carrier,
                        acquisitionId,
                        attemptId,
                        session,
                        acknowledgement
                );

        completion.leaseToRelease().ifPresent(
                lease -> releaseUnboundLease(
                        lease,
                        PlayerSessionLeaseBindingResult.DISCONNECTED
                )
        );

        if (!completion.shouldRespond()) {
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
                acknowledgement.successful(),
                acknowledgement.message()
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
            leaseBindingRegistry.failReleaseBeforeExternalAttachment(
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

        boolean releaseAttached =
                leaseBindingRegistry.attachReleaseCompletion(
                        lease,
                        releaseStage
                );

        if (!releaseAttached) {
            IllegalStateException exception =
                    new IllegalStateException(
                            "Release completion stage could not be "
                                    + "attached to the tracked lease"
                    );

            leaseBindingRegistry.failReleaseBeforeExternalAttachment(
                    lease,
                    exception
            );

            logger.error(
                    "No se pudo asociar la liberaciÃ³n del lease "
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
                                releaseStage,
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
                            releaseStage,
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

package com.theosfera.proxy;

import com.google.inject.Inject;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthCheckScheduler;
import com.theosfera.proxy.backend.BackendHealthCheckTask;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendIdentityProvider;
import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.theosfera.proxy.backend.BackendPingEmitter;
import com.theosfera.proxy.backend.BackendPolicyConfigLoader;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import com.theosfera.proxy.command.LobbyCommand;
import com.theosfera.proxy.command.LobbyCommandRegistration;
import com.theosfera.proxy.command.LobbyTransferService;
import com.theosfera.proxy.command.ProxyStatusCommand;
import com.theosfera.proxy.command.ProxyStatusCommandRegistration;
import com.theosfera.proxy.command.RawServerCommandHardening;
import com.theosfera.proxy.control.BackendControlPingTransport;
import com.theosfera.proxy.control.BackendControlRuntime;
import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.PlayerPresenceCoordinator;
import com.theosfera.proxy.coordination.PlayerSessionCoordinator;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyInstanceIdentityConfigLoader;
import com.theosfera.proxy.coordination.distributed.redis.RedisCoordinationConfig;
import com.theosfera.proxy.coordination.velocity.VelocityRedisCoordinationBootstrap;
import com.theosfera.proxy.failover.BackendKickFailoverListener;
import com.theosfera.proxy.failover.BackendKickFailoverService;
import com.theosfera.proxy.failover.DistributedBackendKickFailoverCoordinator;
import com.theosfera.proxy.failover.PendingPlayerFailoverRegistry;
import com.theosfera.proxy.messaging.ProtocolChannel;
import com.theosfera.proxy.messaging.ProtocolChannelRegistration;
import com.theosfera.proxy.messaging.ProtocolMessageDispatcher;
import com.theosfera.proxy.messaging.ProtocolMessageListener;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import com.theosfera.proxy.messaging.handler.PlayerAuthenticatedMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerServerReadyMessageHandler;
import com.theosfera.proxy.messaging.handler.TransferRequestMessageHandler;
import com.theosfera.proxy.observability.BackendOperationalSnapshotService;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerAuthenticationAckSender;
import com.theosfera.proxy.session.PlayerDisconnectListener;
import com.theosfera.proxy.session.PlayerPresenceRuntimeService;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.session.PlayerSessionLeaseBindingRegistry;
import com.theosfera.proxy.session.PlayerSessionReleaseService;
import com.theosfera.proxy.session.PlayerSessionRenewalService;
import com.theosfera.proxy.session.PlayerSessionShutdownReleaseService;
import com.theosfera.proxy.session.velocity.VelocityPlayerPresenceRenewalScheduler;
import com.theosfera.proxy.session.velocity.VelocityPlayerSessionAcquisitionTimeoutScheduler;
import com.theosfera.proxy.session.velocity.VelocityPlayerSessionReleaseTimeoutScheduler;
import com.theosfera.proxy.session.velocity.VelocityPlayerSessionRenewalScheduler;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
import com.theosfera.proxy.transfer.DistributedBackendCapacityReleaseService;
import com.theosfera.proxy.transfer.DistributedBackendCapacityRuntime;
import com.theosfera.proxy.transfer.DistributedPlayerTransferRetryCoordinator;
import com.theosfera.proxy.transfer.DistributedResolvedTargetAllocationService;
import com.theosfera.proxy.transfer.PendingPlayerTransferRegistry;
import com.theosfera.proxy.transfer.PlayerTransferExecutor;
import com.theosfera.proxy.transfer.TransferResultSender;
import com.theosfera.proxy.transfer.TransferTargetResolver;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "theosferaproxy",
        name = "TheosferaProxy",
        version = "0.1.0-SNAPSHOT",
        description = "Proxy y coordinador global de la network Theosfera.",
        url = "https://github.com/HarriOcho/TheosferaProxy",
        authors = {"HarriOcho"}
)
public final class TheosferaProxy {

    private static final long SHUTDOWN_SESSION_RELEASE_TIMEOUT_SECONDS = 5L;

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final ProtocolChannelRegistration channelRegistration;
    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final PlayerSessionLeaseBindingRegistry sessionLeaseBindingRegistry;
    private final PlayerServerPresenceRegistry presenceRegistry;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final BackendBootstrapRegistry bootstrapRegistry;
    private final PendingPlayerFailoverRegistry failoverRegistry;
    private final UUID incarnationId;
    private final VelocityPlayerSessionReleaseTimeoutScheduler releaseTimeoutScheduler;
    private final BackendHealthRegistry healthRegistry;
    private final PendingBackendPingRegistry pendingPingRegistry;
    private final RawServerCommandHardening rawServerCommandHardening;

    private PlayerSessionCoordinator sessionCoordinator;
    private PlayerPresenceCoordinator presenceCoordinator;
    private PlayerPresenceRuntimeService presenceRuntimeService;
    private PlayerDisconnectListener playerDisconnectListener;
    private PlayerSessionReleaseService releaseService;
    private PlayerSessionRenewalService sessionRenewalService;
    private PlayerSessionShutdownReleaseService shutdownReleaseService;
    private DistributedBackendCapacityRuntime distributedBackendCapacityRuntime;
    private BackendControlRuntime backendControlRuntime;
    private ProtocolMessageListener protocolMessageListener;
    private ProxyInstanceIdentity proxyInstanceIdentity;
    private BackendKickFailoverListener backendKickFailoverListener;
    private LobbyCommandRegistration lobbyCommandRegistration;
    private ProxyStatusCommandRegistration proxyStatusCommandRegistration;
    private BackendHealthCheckScheduler healthCheckScheduler;
    private VelocityRedisCoordinationBootstrap coordinationBootstrap;
    private boolean operationalSurfaceActive;

    @Inject
    public TheosferaProxy(
            final ProxyServer proxyServer,
            final Logger logger,
            @DataDirectory final Path dataDirectory
    ) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.channelRegistration = new ProtocolChannelRegistration(proxyServer.getChannelRegistrar());
        this.sessionRegistry = new AuthenticatedPlayerSessionRegistry();
        this.sessionLeaseBindingRegistry = new PlayerSessionLeaseBindingRegistry();
        this.presenceRegistry = new PlayerServerPresenceRegistry(sessionRegistry);
        this.transferRegistry = new PendingPlayerTransferRegistry();
        this.bootstrapRegistry = new BackendBootstrapRegistry();
        this.failoverRegistry = new PendingPlayerFailoverRegistry();
        this.rawServerCommandHardening = new RawServerCommandHardening(proxyServer, this);

        Clock clock = Clock.systemUTC();
        this.healthRegistry = new BackendHealthRegistry(clock, Duration.ofSeconds(15));
        this.pendingPingRegistry = new PendingBackendPingRegistry(clock, Duration.ofSeconds(10));
        this.incarnationId = UUID.randomUUID();
        this.releaseTimeoutScheduler = new VelocityPlayerSessionReleaseTimeoutScheduler(proxyServer, this);
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        rawServerCommandHardening.install();
        initializeProxyInstanceIdentity();

        coordinationBootstrap = new VelocityRedisCoordinationBootstrap(
                proxyServer,
                this,
                dataDirectory,
                proxyInstanceIdentity,
                logger,
                Clock.systemUTC()
        );

        final boolean coordinationReady;
        try {
            coordinationReady = coordinationBootstrap.start().toCompletableFuture().join();
        } catch (RuntimeException exception) {
            logger.error(
                    "TheosferaProxy quedara cerrado porque la coordinacion Redis no pudo inicializarse.",
                    exception
            );
            return;
        }

        if (!coordinationReady) {
            logger.error(
                    "TheosferaProxy quedara cerrado porque no pudo adquirir su membresia distribuida."
            );
            return;
        }

        try {
            initializeDistributedPlayerSessions();
            initializeProtocolMessaging();
            activateOperationalSurface();
        } catch (RuntimeException exception) {
            logger.error(
                    "Fallo al activar la superficie operativa del Proxy despues de adquirir membership.",
                    exception
            );
            coordinationBootstrap.beginStopping();
            deactivateOperationalSurface();
            clearRuntimeRegistries();
            coordinationBootstrap.stop().toCompletableFuture().join();
            throw exception;
        }

        logger.info("Canal de protocolo registrado: {}.", ProtocolChannel.IDENTIFIER.getId());
        logger.info(
                "TheosferaProxy iniciado correctamente con membership, sesiones y presencia Redis autoritativas; capacidad distribuida preparada."
        );
    }

    @Subscribe
    public void onProxyShutdown(final ProxyShutdownEvent event) {
        if (coordinationBootstrap != null) {
            coordinationBootstrap.beginStopping();
        }

        deactivateOperationalSurface();
        releaseBoundPlayerSessionsBeforeShutdown();
        clearRuntimeRegistries();

        if (coordinationBootstrap != null) {
            try {
                boolean released = coordinationBootstrap.stop().toCompletableFuture().join();
                if (!released) {
                    logger.warn(
                            "El Proxy se apago sin confirmar la liberacion exacta de membership Redis."
                    );
                }
            } catch (RuntimeException exception) {
                logger.warn("Fallo durante el cierre de la coordinacion Redis.", exception);
            }
        }

        rawServerCommandHardening.uninstall();
        logger.info("TheosferaProxy apagado correctamente.");
    }

    private void initializeProxyInstanceIdentity() {
        if (proxyInstanceIdentity != null) {
            throw new IllegalStateException("Proxy instance identity is already initialized");
        }
        proxyInstanceIdentity = new ProxyInstanceIdentity(
                new ProxyInstanceIdentityConfigLoader(dataDirectory).loadProxyName(),
                incarnationId
        );
    }

    private void initializeDistributedPlayerSessions() {
        if (coordinationBootstrap == null
                || coordinationBootstrap.state() != CoordinationState.HEALTHY) {
            throw new IllegalStateException(
                    "Distributed player sessions require healthy Redis coordination"
            );
        }

        if (sessionCoordinator != null
                || presenceCoordinator != null
                || presenceRuntimeService != null
                || releaseService != null
                || playerDisconnectListener != null
                || sessionRenewalService != null
                || shutdownReleaseService != null) {
            throw new IllegalStateException(
                    "Distributed player session runtime is already initialized"
            );
        }

        RedisCoordinationConfig config = coordinationBootstrap.config();

        sessionCoordinator = coordinationBootstrap.createPlayerSessionCoordinator(sessionRegistry);
        presenceCoordinator = coordinationBootstrap.createPlayerPresenceCoordinator();

        releaseService = new PlayerSessionReleaseService(
                sessionCoordinator,
                sessionLeaseBindingRegistry,
                releaseTimeoutScheduler,
                logger
        );

        presenceRuntimeService = new PlayerPresenceRuntimeService(
                proxyServer,
                presenceCoordinator,
                sessionLeaseBindingRegistry,
                presenceRegistry,
                new VelocityPlayerPresenceRenewalScheduler(proxyServer, this),
                config.playerSessionRenewInterval(),
                logger
        );

        playerDisconnectListener = new PlayerDisconnectListener(
                sessionLeaseBindingRegistry,
                presenceRegistry,
                transferRegistry,
                sessionRegistry,
                releaseService,
                presenceRuntimeService,
                logger
        );

        sessionRenewalService = new PlayerSessionRenewalService(
                proxyServer,
                sessionCoordinator,
                sessionLeaseBindingRegistry,
                sessionRegistry,
                new VelocityPlayerSessionRenewalScheduler(proxyServer, this),
                Clock.systemUTC(),
                config.playerSessionTtl(),
                config.playerSessionRenewInterval(),
                logger
        );

        shutdownReleaseService = new PlayerSessionShutdownReleaseService(
                proxyServer,
                sessionCoordinator,
                sessionLeaseBindingRegistry,
                presenceRegistry,
                presenceRuntimeService,
                logger
        );
    }

    private void initializeDistributedBackendCapacity(
            BackendAuthorizationPolicy authorizationPolicy,
            TransferTargetResolver targetResolver
    ) {
        BackendAuthorizationPolicy nonNullPolicy = Objects.requireNonNull(
                authorizationPolicy,
                "authorizationPolicy cannot be null"
        );
        TransferTargetResolver nonNullResolver = Objects.requireNonNull(
                targetResolver,
                "targetResolver cannot be null"
        );

        if (coordinationBootstrap == null
                || coordinationBootstrap.state() != CoordinationState.HEALTHY) {
            throw new IllegalStateException(
                    "Distributed backend capacity requires healthy Redis coordination"
            );
        }

        if (distributedBackendCapacityRuntime != null) {
            throw new IllegalStateException(
                    "Distributed backend capacity runtime is already initialized"
            );
        }

        RedisCoordinationConfig config = coordinationBootstrap.config();

        distributedBackendCapacityRuntime =
                DistributedBackendCapacityRuntime.create(
                        coordinationBootstrap.createBackendOccupancyCoordinator(
                                nonNullPolicy.authorizedBackendNames()
                        ),
                        coordinationBootstrap.createBackendCapacityCoordinator(
                                config.backendCapacityReservationTtl()
                        ),
                        nonNullResolver,
                        transferRegistry,
                        sessionLeaseBindingRegistry,
                        logger
                );
    }

    private void bindDistributedCapacityHandoff() {
        requireOperationalSessionRuntime();
        requireOperationalDistributedCapacityRuntime();

        presenceRuntimeService.configureCapacityHandoffLifecycle(
                distributedBackendCapacityRuntime.handoffService()
        );
        playerDisconnectListener.configureCapacityHandoffLifecycle(
                distributedBackendCapacityRuntime.handoffService()
        );
    }

    private void activateOperationalSurface() {
        if (operationalSurfaceActive) {
            throw new IllegalStateException("Proxy operational surface is already active");
        }

        requireOperationalSessionRuntime();
        requireOperationalDistributedCapacityRuntime();
        if (backendControlRuntime == null) {
            throw new IllegalStateException(
                    "Backend control runtime is not initialized"
            );
        }
        operationalSurfaceActive = true;

        backendControlRuntime.start();
        channelRegistration.register();
        lobbyCommandRegistration.register();
        proxyStatusCommandRegistration.register();
        proxyServer.getEventManager().register(this, protocolMessageListener);
        proxyServer.getEventManager().register(this, playerDisconnectListener);
        proxyServer.getEventManager().register(this, backendKickFailoverListener);

        if (healthCheckScheduler != null) {
            healthCheckScheduler.start();
        }

        sessionRenewalService.start();
        presenceRuntimeService.start();
    }

    private void deactivateOperationalSurface() {
        if (!operationalSurfaceActive) {
            return;
        }

        operationalSurfaceActive = false;

        if (healthCheckScheduler != null) {
            healthCheckScheduler.stop();
        }
        if (backendControlRuntime != null) {
            backendControlRuntime.stop();
        }
        if (presenceRuntimeService != null) {
            presenceRuntimeService.stop();
        }
        if (sessionRenewalService != null) {
            sessionRenewalService.stop();
        }
        if (protocolMessageListener != null) {
            proxyServer.getEventManager().unregisterListener(this, protocolMessageListener);
        }
        if (lobbyCommandRegistration != null) {
            lobbyCommandRegistration.unregister();
        }
        if (proxyStatusCommandRegistration != null) {
            proxyStatusCommandRegistration.unregister();
        }
        if (playerDisconnectListener != null) {
            proxyServer.getEventManager().unregisterListener(this, playerDisconnectListener);
        }
        if (backendKickFailoverListener != null) {
            proxyServer.getEventManager().unregisterListener(this, backendKickFailoverListener);
        }

        channelRegistration.unregister();
        logger.info("Canal de protocolo desregistrado: {}.", ProtocolChannel.IDENTIFIER.getId());
    }

    private void releaseBoundPlayerSessionsBeforeShutdown() {
        if (shutdownReleaseService == null) {
            return;
        }

        try {
            PlayerSessionShutdownReleaseService.ReleaseSummary summary = shutdownReleaseService
                    .releaseBoundSessions()
                    .toCompletableFuture()
                    .orTimeout(SHUTDOWN_SESSION_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();

            if (!summary.complete()) {
                logger.warn(
                        "Shutdown libero {}/{} sesiones Redis; las restantes dependeran de TTL.",
                        summary.released(),
                        summary.attempted()
                );
            }
        } catch (RuntimeException exception) {
            logger.warn(
                    "No se pudieron drenar todas las sesiones Redis antes del shutdown; TTL actuara como fallback.",
                    exception
            );
        }
    }

    private void clearRuntimeRegistries() {
        if (backendControlRuntime != null) {
            backendControlRuntime.stop();
            backendControlRuntime = null;
        }
        bootstrapRegistry.clear();
        failoverRegistry.clear();
        transferRegistry.clear();
        presenceRegistry.clear();
        if (releaseService != null) {
            releaseService.clear();
        }
        sessionLeaseBindingRegistry.clear();
        sessionRegistry.clear();
        pendingPingRegistry.clear();
        healthRegistry.clear();
        if (distributedBackendCapacityRuntime != null) {
            distributedBackendCapacityRuntime.handoffService().clear();
        }
        distributedBackendCapacityRuntime = null;
    }

    private void requireOperationalSessionRuntime() {
        if (sessionCoordinator == null
                || presenceCoordinator == null
                || presenceRuntimeService == null
                || releaseService == null
                || playerDisconnectListener == null
                || sessionRenewalService == null
                || shutdownReleaseService == null) {
            throw new IllegalStateException(
                    "Distributed player session and presence runtime is not initialized"
            );
        }
    }

    private void requireOperationalDistributedCapacityRuntime() {
        if (distributedBackendCapacityRuntime == null) {
            throw new IllegalStateException(
                    "Distributed backend capacity runtime is not initialized"
            );
        }
    }

    private void initializeProtocolMessaging() {
        if (protocolMessageListener != null
                || backendControlRuntime != null) {
            throw new IllegalStateException(
                    "Protocol messaging is already initialized"
            );
        }
        if (proxyInstanceIdentity == null) {
            throw new IllegalStateException("Proxy instance identity must be initialized first");
        }

        requireOperationalSessionRuntime();

        BackendAuthorizationPolicy authorizationPolicy =
                new BackendPolicyConfigLoader(dataDirectory).load();
        backendControlRuntime = BackendControlRuntime.create(
                dataDirectory,
                authorizationPolicy,
                pendingPingRegistry,
                healthRegistry,
                logger,
                identity -> bootstrapRegistry.removeByTarget(
                        identity.serverName()
                )
        );
        BackendIdentityProvider controlIdentityProvider =
                backendControlRuntime.requireIdentityProvider();
        BackendMessageAuthorizer messageAuthorizer =
                new BackendMessageAuthorizer(controlIdentityProvider);
        ProtocolMessageSender messageSender = new ProtocolMessageSender();
        BackendPingEmitter pingEmitter = new BackendPingEmitter(
                Clock.systemUTC(),
                UUID::randomUUID,
                pendingPingRegistry,
                new BackendControlPingTransport(
                        backendControlRuntime.requireMessageSender()
                ),
                logger
        );
        BackendHealthCheckTask healthCheckTask = new BackendHealthCheckTask(
                authorizationPolicy,
                pingEmitter,
                logger
        );
        healthCheckScheduler = new BackendHealthCheckScheduler(
                proxyServer,
                this,
                healthCheckTask,
                logger
        );

        PlayerAuthenticationAckSender authenticationAckSender =
                new PlayerAuthenticationAckSender(messageSender, logger);
        VelocityPlayerSessionAcquisitionTimeoutScheduler acquisitionTimeoutScheduler =
                new VelocityPlayerSessionAcquisitionTimeoutScheduler(proxyServer, this);
        TransferTargetResolver targetResolver = new TransferTargetResolver(
                proxyServer,
                authorizationPolicy,
                controlIdentityProvider,
                healthRegistry
        );
        initializeDistributedBackendCapacity(
                authorizationPolicy,
                targetResolver
        );
        bindDistributedCapacityHandoff();

        PlayerTransferExecutor transferExecutor = new PlayerTransferExecutor();
        DistributedPlayerTransferRetryCoordinator distributedPlayerTransferRetryCoordinator =
                new DistributedPlayerTransferRetryCoordinator(
                        bootstrapRegistry,
                        transferRegistry,
                        distributedBackendCapacityRuntime.allocationService(),
                        transferExecutor,
                        new DistributedBackendCapacityReleaseService(
                                distributedBackendCapacityRuntime.capacityCoordinator(),
                                logger
                        ),
                        distributedBackendCapacityRuntime.handoffService(),
                        logger
                );
        TransferResultSender transferResultSender = new TransferResultSender(messageSender, logger);
        LobbyTransferService lobbyTransferService = new LobbyTransferService(
                sessionRegistry,
                controlIdentityProvider,
                distributedPlayerTransferRetryCoordinator
        );

        DistributedResolvedTargetAllocationService kickFailoverAllocationService =
                new DistributedResolvedTargetAllocationService(
                        targetResolver,
                        sessionLeaseBindingRegistry,
                        distributedBackendCapacityRuntime.occupancyCoordinator(),
                        distributedBackendCapacityRuntime.capacityCoordinator()
                );
        DistributedBackendKickFailoverCoordinator kickFailoverCoordinator =
                new DistributedBackendKickFailoverCoordinator(
                        kickFailoverAllocationService,
                        failoverRegistry,
                        distributedBackendCapacityRuntime.releaseService(),
                        distributedBackendCapacityRuntime.handoffService(),
                        logger
                );
        backendKickFailoverListener = new BackendKickFailoverListener(
                new BackendKickFailoverService(
                        sessionRegistry,
                        controlIdentityProvider,
                        kickFailoverCoordinator
                )
        );
        lobbyCommandRegistration = new LobbyCommandRegistration(
                proxyServer,
                this,
                new LobbyCommand(lobbyTransferService)
        );

        BackendOperationalSnapshotService operationalSnapshotService =
                new BackendOperationalSnapshotService(
                        proxyServer,
                        authorizationPolicy,
                        controlIdentityProvider,
                        healthRegistry,
                        bootstrapRegistry
                );
        proxyStatusCommandRegistration = new ProxyStatusCommandRegistration(
                proxyServer,
                this,
                new ProxyStatusCommand(operationalSnapshotService)
        );

        ProtocolMessageDispatcher dispatcher = new ProtocolMessageDispatcher(
                List.of(
                        new PlayerAuthenticatedMessageHandler(
                                sessionCoordinator,
                                sessionLeaseBindingRegistry,
                                proxyInstanceIdentity,
                                authenticationAckSender,
                                acquisitionTimeoutScheduler,
                                releaseService,
                                logger
                        ),
                        new PlayerServerReadyMessageHandler(
                                presenceRuntimeService,
                                logger
                        ),
                        new TransferRequestMessageHandler(
                                proxyServer,
                                controlIdentityProvider,
                                sessionRegistry,
                                presenceRegistry,
                                distributedPlayerTransferRetryCoordinator,
                                transferResultSender,
                                logger
                        )
                )
        );

        protocolMessageListener = new ProtocolMessageListener(
                logger,
                messageAuthorizer,
                dispatcher,
                () -> coordinationBootstrap != null
                        && coordinationBootstrap.state() == CoordinationState.HEALTHY
        );

        logger.info(
                "Política de backends cargada: {} autorizados.",
                authorizationPolicy.authorizedBackendNames().size()
        );
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }
}

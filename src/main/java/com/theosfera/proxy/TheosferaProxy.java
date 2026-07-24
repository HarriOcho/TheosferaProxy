package com.theosfera.proxy;

import com.google.inject.Inject;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthCheckScheduler;
import com.theosfera.proxy.backend.BackendHealthCheckTask;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.theosfera.proxy.backend.BackendPolicyConfigLoader;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendPingConnectionResolver;
import com.theosfera.proxy.backend.BackendPingEmitter;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import com.theosfera.proxy.command.LobbyCommand;
import com.theosfera.proxy.command.LobbyCommandRegistration;
import com.theosfera.proxy.command.LobbyTransferService;
import com.theosfera.proxy.failover.BackendKickFailoverListener;
import com.theosfera.proxy.failover.BackendKickFailoverService;
import com.theosfera.proxy.failover.PendingPlayerFailoverRegistry;
import com.theosfera.proxy.messaging.ProtocolChannel;
import com.theosfera.proxy.messaging.ProtocolChannelRegistration;
import com.theosfera.proxy.messaging.ProtocolMessageDispatcher;
import com.theosfera.proxy.messaging.ProtocolMessageListener;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import com.theosfera.proxy.messaging.handler.BackendHelloMessageHandler;
import com.theosfera.proxy.messaging.handler.PingMessageHandler;
import com.theosfera.proxy.messaging.handler.PongMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerAuthenticatedMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerServerReadyMessageHandler;
import com.theosfera.proxy.messaging.handler.TransferRequestMessageHandler;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerAuthenticationAckSender;
import com.theosfera.proxy.session.PlayerDisconnectListener;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.transfer.BackendBootstrapRegistry;
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
import java.util.UUID;

@Plugin(
        id = "theosferaproxy",
        name = "TheosferaProxy",
        version = "0.1.0-SNAPSHOT",
        description = "Proxy y coordinador global de la network Theosfera.",
        url = "https://github.com/HarriOcho/TheosferaProxy",
        authors = {"HarriOcho"}
)
public final class TheosferaProxy {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final ProtocolChannelRegistration channelRegistration;
    private final BackendIdentityRegistry identityRegistry;
    private final AuthenticatedPlayerSessionRegistry sessionRegistry;
    private final PlayerServerPresenceRegistry presenceRegistry;
    private final PendingPlayerTransferRegistry transferRegistry;
    private final BackendBootstrapRegistry bootstrapRegistry;
    private final PendingPlayerFailoverRegistry failoverRegistry;
    private final PlayerDisconnectListener playerDisconnectListener;
    private final BackendHealthRegistry healthRegistry;
    private final PendingBackendPingRegistry pendingPingRegistry;

    private ProtocolMessageListener protocolMessageListener;
    private BackendKickFailoverListener backendKickFailoverListener;
    private LobbyCommandRegistration lobbyCommandRegistration;
    private BackendHealthCheckScheduler healthCheckScheduler;

    @Inject
    public TheosferaProxy(
            final ProxyServer proxyServer,
            final Logger logger,
            @DataDirectory final Path dataDirectory
    ) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

        this.channelRegistration =
                new ProtocolChannelRegistration(
                        proxyServer.getChannelRegistrar()
                );

        this.identityRegistry =
                new BackendIdentityRegistry();

        this.sessionRegistry =
                new AuthenticatedPlayerSessionRegistry();

        this.presenceRegistry =
                new PlayerServerPresenceRegistry(
                        sessionRegistry
                );

        this.transferRegistry =
                new PendingPlayerTransferRegistry();

        this.bootstrapRegistry =
                new BackendBootstrapRegistry();

        this.failoverRegistry =
                new PendingPlayerFailoverRegistry();

        Clock clock = Clock.systemUTC();

        this.healthRegistry =
                new BackendHealthRegistry(
                        clock,
                        Duration.ofSeconds(15)
                );

        this.pendingPingRegistry =
                new PendingBackendPingRegistry(
                        clock,
                        Duration.ofSeconds(10)
                );

        this.playerDisconnectListener =
                new PlayerDisconnectListener(
                        sessionRegistry,
                        presenceRegistry,
                        transferRegistry,
                        logger
                );
    }

    @Subscribe
    public void onProxyInitialization(
            final ProxyInitializeEvent event
    ) {
        initializeProtocolMessaging();

        channelRegistration.register();
        lobbyCommandRegistration.register();

        proxyServer.getEventManager().register(
                this,
                protocolMessageListener
        );

        proxyServer.getEventManager().register(
                this,
                playerDisconnectListener
        );

        proxyServer.getEventManager().register(
                this,
                backendKickFailoverListener
        );

        if (healthCheckScheduler != null) {
            healthCheckScheduler.start();
        }

        logger.info(
                "Canal de protocolo registrado: {}.",
                ProtocolChannel.IDENTIFIER.getId()
        );

        logger.info(
                "TheosferaProxy iniciado correctamente."
        );
    }

    @Subscribe
    public void onProxyShutdown(
            final ProxyShutdownEvent event
    ) {
        if (healthCheckScheduler != null) {
            healthCheckScheduler.stop();
        }

        if (protocolMessageListener != null) {
            proxyServer.getEventManager().unregisterListener(
                    this,
                    protocolMessageListener
            );
        }

        if (lobbyCommandRegistration != null) {
            lobbyCommandRegistration.unregister();
        }

        proxyServer.getEventManager().unregisterListener(
                this,
                playerDisconnectListener
        );

        if (backendKickFailoverListener != null) {
            proxyServer.getEventManager().unregisterListener(
                    this,
                    backendKickFailoverListener
            );
        }

        bootstrapRegistry.clear();
        failoverRegistry.clear();
        transferRegistry.clear();
        presenceRegistry.clear();
        sessionRegistry.clear();
        pendingPingRegistry.clear();
        healthRegistry.clear();
        identityRegistry.clear();
        channelRegistration.unregister();

        logger.info(
                "Canal de protocolo desregistrado: {}.",
                ProtocolChannel.IDENTIFIER.getId()
        );

        logger.info(
                "TheosferaProxy apagado correctamente."
        );
    }

    private void initializeProtocolMessaging() {
        if (protocolMessageListener != null) {
            throw new IllegalStateException(
                    "Protocol messaging is already initialized"
            );
        }

        BackendAuthorizationPolicy authorizationPolicy =
                new BackendPolicyConfigLoader(
                        dataDirectory
                ).load();

        BackendMessageAuthorizer messageAuthorizer =
                new BackendMessageAuthorizer(
                        identityRegistry
                );

        ProtocolMessageSender messageSender =
                new ProtocolMessageSender();

        BackendPingConnectionResolver pingConnectionResolver =
                new BackendPingConnectionResolver(
                        proxyServer
                );

        BackendPingEmitter pingEmitter =
                new BackendPingEmitter(
                        Clock.systemUTC(),
                        UUID::randomUUID,
                        pendingPingRegistry,
                        pingConnectionResolver,
                        messageSender,
                        logger
                );

        BackendHealthCheckTask healthCheckTask =
                new BackendHealthCheckTask(
                        authorizationPolicy,
                        pingEmitter,
                        logger
                );

        healthCheckScheduler =
                new BackendHealthCheckScheduler(
                        proxyServer,
                        this,
                        healthCheckTask,
                        logger
                );

        PlayerAuthenticationAckSender
                authenticationAckSender =
                new PlayerAuthenticationAckSender(
                        messageSender,
                        logger
                );

        TransferTargetResolver targetResolver =
                new TransferTargetResolver(
                        proxyServer,
                        authorizationPolicy,
                        identityRegistry
                );

        PlayerTransferExecutor transferExecutor =
                new PlayerTransferExecutor();

        TransferResultSender transferResultSender =
                new TransferResultSender(
                        messageSender,
                        logger
                );

        LobbyTransferService lobbyTransferService =
                new LobbyTransferService(
                        sessionRegistry,
                        transferRegistry,
                        bootstrapRegistry,
                        targetResolver,
                        transferExecutor
                );

        backendKickFailoverListener =
                new BackendKickFailoverListener(
                        new BackendKickFailoverService(
                                sessionRegistry,
                                identityRegistry,
                                targetResolver,
                                bootstrapRegistry,
                                failoverRegistry
                        )
                );

        lobbyCommandRegistration =
                new LobbyCommandRegistration(
                        proxyServer,
                        this,
                        new LobbyCommand(lobbyTransferService)
                );

        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(
                                new BackendHelloMessageHandler(
                                        authorizationPolicy,
                                        identityRegistry,
                                        bootstrapRegistry,
                                        messageSender,
                                        logger
                                ),
                                new PingMessageHandler(
                                        messageSender,
                                        logger
                                ),
                                new PongMessageHandler(
                                        pendingPingRegistry,
                                        healthRegistry,
                                        logger
                                ),
                                new PlayerAuthenticatedMessageHandler(
                                        sessionRegistry,
                                        authenticationAckSender,
                                        logger
                                ),
                                new PlayerServerReadyMessageHandler(
                                        presenceRegistry,
                                        logger
                                ),
                                new TransferRequestMessageHandler(
                                        proxyServer,
                                        identityRegistry,
                                        sessionRegistry,
                                        presenceRegistry,
                                        transferRegistry,
                                        bootstrapRegistry,
                                        targetResolver,
                                        transferExecutor,
                                        transferResultSender,
                                        logger
                                )
                        )
                );

        protocolMessageListener =
                new ProtocolMessageListener(
                        logger,
                        messageAuthorizer,
                        dispatcher
                );

        logger.info(
                "Política de backends cargada: {} autorizados.",
                authorizationPolicy
                        .allowedBackends()
                        .size()
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

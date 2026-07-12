package com.theosfera.proxy;

import com.google.inject.Inject;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendIdentityRegistry;
import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.theosfera.proxy.backend.BackendPolicyConfigLoader;
import com.theosfera.proxy.messaging.ProtocolChannel;
import com.theosfera.proxy.messaging.ProtocolChannelRegistration;
import com.theosfera.proxy.messaging.ProtocolMessageDispatcher;
import com.theosfera.proxy.messaging.ProtocolMessageListener;
import com.theosfera.proxy.messaging.ProtocolMessageSender;
import com.theosfera.proxy.messaging.handler.BackendHelloMessageHandler;
import com.theosfera.proxy.messaging.handler.PingMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerAuthenticatedMessageHandler;
import com.theosfera.proxy.messaging.handler.PlayerServerReadyMessageHandler;
import com.theosfera.proxy.messaging.handler.TransferRequestMessageHandler;
import com.theosfera.proxy.session.AuthenticatedPlayerSessionRegistry;
import com.theosfera.proxy.session.PlayerServerPresenceRegistry;
import com.theosfera.proxy.session.PlayerDisconnectListener;
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
import java.util.List;

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
    private final PlayerDisconnectListener playerDisconnectListener;

    private ProtocolMessageListener protocolMessageListener;

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
        proxyServer.getEventManager().register(
                this,
                protocolMessageListener
        );

        proxyServer.getEventManager().register(
                this,
                playerDisconnectListener
        );

        logger.info(
                "Canal de protocolo registrado: {}.",
                ProtocolChannel.IDENTIFIER.getId()
        );
        logger.info("TheosferaProxy iniciado correctamente.");
    }

    @Subscribe
    public void onProxyShutdown(
            final ProxyShutdownEvent event
    ) {
        if (protocolMessageListener != null) {
            proxyServer.getEventManager().unregisterListener(
                    this,
                    protocolMessageListener
            );
        }

        proxyServer.getEventManager().unregisterListener(
                this,
                playerDisconnectListener
        );

        transferRegistry.clear();
        presenceRegistry.clear();
        sessionRegistry.clear();
        identityRegistry.clear();
        channelRegistration.unregister();

        logger.info(
                "Canal de protocolo desregistrado: {}.",
                ProtocolChannel.IDENTIFIER.getId()
        );
        logger.info("TheosferaProxy apagado correctamente.");
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

        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(
                        List.of(
                                new BackendHelloMessageHandler(
                                        authorizationPolicy,
                                        identityRegistry,
                                        messageSender,
                                        logger
                                ),
                                new PingMessageHandler(
                                        messageSender,
                                        logger
                                ),
                                new PlayerAuthenticatedMessageHandler(
                                        sessionRegistry,
                                        logger
                                ),
                                new PlayerServerReadyMessageHandler(
                                        presenceRegistry,
                                        logger
                                ),
                                new TransferRequestMessageHandler(
                                        proxyServer,
                                        sessionRegistry,
                                        presenceRegistry,
                                        transferRegistry,
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
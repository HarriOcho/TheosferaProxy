package com.theosfera.proxy;

import com.google.inject.Inject;
import com.theosfera.proxy.messaging.ProtocolChannel;
import com.theosfera.proxy.messaging.ProtocolChannelRegistration;
import com.theosfera.proxy.messaging.ProtocolMessageDispatcher;
import com.theosfera.proxy.messaging.ProtocolMessageListener;
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
    private final ProtocolMessageListener protocolMessageListener;

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
        ProtocolMessageDispatcher dispatcher =
                new ProtocolMessageDispatcher(List.of());

        this.protocolMessageListener =
                new ProtocolMessageListener(
                        logger,
                        dispatcher
                );
    }

    @Subscribe
    public void onProxyInitialization(
            final ProxyInitializeEvent event
    ) {
        channelRegistration.register();
        proxyServer.getEventManager().register(
                this,
                protocolMessageListener
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
        proxyServer.getEventManager().unregisterListener(
                this,
                protocolMessageListener
        );
        channelRegistration.unregister();

        logger.info(
                "Canal de protocolo desregistrado: {}.",
                ProtocolChannel.IDENTIFIER.getId()
        );
        logger.info("TheosferaProxy apagado correctamente.");
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
package com.theosfera.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

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

    @Inject
    public TheosferaProxy(
            final ProxyServer proxyServer,
            final Logger logger,
            @DataDirectory final Path dataDirectory
    ) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(
            final ProxyInitializeEvent event
    ) {
        logger.info("TheosferaProxy iniciado correctamente.");
    }

    @Subscribe
    public void onProxyShutdown(
            final ProxyShutdownEvent event
    ) {
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
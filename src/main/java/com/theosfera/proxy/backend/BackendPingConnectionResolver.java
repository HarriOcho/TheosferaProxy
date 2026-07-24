package com.theosfera.proxy.backend;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Objects;
import java.util.Optional;

public final class BackendPingConnectionResolver {

    private final ProxyServer proxyServer;

    public BackendPingConnectionResolver(ProxyServer proxyServer) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
    }

    public Optional<ServerConnection> resolve(String serverName) {
        String normalizedServerName = requireServerName(serverName);

        Optional<RegisteredServer> registeredServer =
                proxyServer.getServer(normalizedServerName);

        if (registeredServer.isEmpty()) {
            return Optional.empty();
        }

        for (Player player
                : registeredServer.orElseThrow()
                .getPlayersConnected()) {
            Optional<ServerConnection> currentServer =
                    player.getCurrentServer();

            if (currentServer.isPresent()
                    && currentServer
                    .orElseThrow()
                    .getServerInfo()
                    .getName()
                    .equals(normalizedServerName)) {
                return currentServer;
            }
        }

        return Optional.empty();
    }

    private static String requireServerName(String serverName) {
        String normalized = Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        ).trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "serverName cannot be blank"
            );
        }

        return normalized;
    }
}

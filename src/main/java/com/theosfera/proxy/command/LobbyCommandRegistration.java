package com.theosfera.proxy.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Objects;

public final class LobbyCommandRegistration {

    private final ProxyServer proxyServer;
    private final Object plugin;
    private final LobbyCommand command;

    private CommandMeta commandMeta;

    public LobbyCommandRegistration(
            ProxyServer proxyServer,
            Object plugin,
            LobbyCommand command
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );

        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin cannot be null"
        );

        this.command = Objects.requireNonNull(
                command,
                "command cannot be null"
        );
    }

    public void register() {
        if (commandMeta != null) {
            return;
        }

        CommandManager commandManager =
                proxyServer.getCommandManager();

        commandMeta =
                commandManager
                        .metaBuilder("hub")
                        .aliases("lobby")
                        .plugin(plugin)
                        .build();

        commandManager.register(
                commandMeta,
                command
        );
    }

    public void unregister() {
        if (commandMeta == null) {
            return;
        }

        proxyServer
                .getCommandManager()
                .unregister(commandMeta);

        commandMeta = null;
    }
}

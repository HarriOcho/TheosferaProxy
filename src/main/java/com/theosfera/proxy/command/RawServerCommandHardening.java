package com.theosfera.proxy.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.Objects;

import static com.theosfera.proxy.ui.TheosferaPalette.AMBER;
import static com.theosfera.proxy.ui.TheosferaPalette.SECONDARY_TEXT;

public final class RawServerCommandHardening {

    static final Component BLOCKED_MESSAGE =
            Component.text(
                    "Los cambios directos de servidor están desactivados en Theosfera.",
                    AMBER
            ).append(Component.text(
                    " Usa /lobby, /lobby switch o los comandos oficiales de Theosfera.",
                    SECONDARY_TEXT
            ));

    private static final String RAW_SERVER_COMMAND = "server";

    private final ProxyServer proxyServer;
    private final Object plugin;

    private boolean installed;

    public RawServerCommandHardening(
            ProxyServer proxyServer,
            Object plugin
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
        this.plugin = Objects.requireNonNull(
                plugin,
                "plugin cannot be null"
        );
    }

    public void install() {
        if (installed) {
            return;
        }

        CommandManager commandManager = proxyServer.getCommandManager();
        EventManager eventManager = proxyServer.getEventManager();

        commandManager.unregister(RAW_SERVER_COMMAND);
        eventManager.register(plugin, this);
        installed = true;
    }

    public void uninstall() {
        if (!installed) {
            return;
        }

        proxyServer
                .getEventManager()
                .unregisterListener(plugin, this);

        installed = false;
    }

    @Subscribe(priority = Short.MIN_VALUE, async = false)
    public void onCommandExecute(CommandExecuteEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        CommandSource source = event.getCommandSource();
        if (!(source instanceof Player player)) {
            return;
        }

        boolean rawServerCommand = isRawServerCommand(event.getCommand())
                || event.getResult()
                .getCommand()
                .map(RawServerCommandHardening::isRawServerCommand)
                .orElse(false);

        if (!rawServerCommand) {
            return;
        }

        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(BLOCKED_MESSAGE);
    }

    static boolean isRawServerCommand(String command) {
        String nonNullCommand = Objects.requireNonNull(
                command,
                "command cannot be null"
        );

        int index = 0;
        while (index < nonNullCommand.length()
                && Character.isWhitespace(nonNullCommand.charAt(index))) {
            index++;
        }

        int tokenStart = index;
        while (index < nonNullCommand.length()
                && !Character.isWhitespace(nonNullCommand.charAt(index))) {
            index++;
        }

        if (tokenStart == index) {
            return false;
        }

        return nonNullCommand
                .regionMatches(
                        true,
                        tokenStart,
                        RAW_SERVER_COMMAND,
                        0,
                        RAW_SERVER_COMMAND.length()
                )
                && index - tokenStart == RAW_SERVER_COMMAND.length();
    }
}

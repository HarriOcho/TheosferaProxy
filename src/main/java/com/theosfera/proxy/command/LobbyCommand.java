package com.theosfera.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.Objects;

import static com.theosfera.proxy.ui.TheosferaPalette.LIGHT_GOLD;
import static com.theosfera.proxy.ui.TheosferaPalette.SECONDARY_TEXT;
import static com.theosfera.proxy.ui.TheosferaPalette.WARM_IVORY;

public final class LobbyCommand implements SimpleCommand {

    static final Component PLAYER_ONLY_MESSAGE =
            Component.text(
                    "Este comando solo está disponible para jugadores.",
                    WARM_IVORY
            );

    static final Component USAGE_MESSAGE =
            Component.text(
                    "Uso: ",
                    SECONDARY_TEXT
            ).append(Component.text(
                    "/lobby [switch]",
                    LIGHT_GOLD
            )).append(Component.text(
                    " o ",
                    SECONDARY_TEXT
            )).append(Component.text(
                    "/hub [switch]",
                    LIGHT_GOLD
            )).append(Component.text(
                    ".",
                    SECONDARY_TEXT
            ));

    private final LobbyTransferService transferService;

    public LobbyCommand(
            LobbyTransferService transferService
    ) {
        this.transferService = Objects.requireNonNull(
                transferService,
                "transferService cannot be null"
        );
    }

    @Override
    public void execute(
            Invocation invocation
    ) {
        CommandSource source = invocation.source();

        if (!(source instanceof Player player)) {
            source.sendMessage(PLAYER_ONLY_MESSAGE);
            return;
        }

        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            transferService.transferToLobby(player);
            return;
        }

        if (arguments.length == 1
                && arguments[0].equalsIgnoreCase("switch")) {
            transferService.switchLobbyInstance(player);
            return;
        }

        player.sendMessage(USAGE_MESSAGE);
    }
}

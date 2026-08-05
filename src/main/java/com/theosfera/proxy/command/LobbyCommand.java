package com.theosfera.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.Objects;

import static com.theosfera.proxy.ui.TheosferaPalette.WARM_IVORY;

public final class LobbyCommand implements SimpleCommand {

    static final Component PLAYER_ONLY_MESSAGE =
            Component.text(
                    "Este comando solo está disponible para jugadores.",
                    WARM_IVORY
            );

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
        CommandSource source =
                invocation.source();

        if (!(source instanceof Player player)) {
            source.sendMessage(PLAYER_ONLY_MESSAGE);
            return;
        }

        transferService.transferToLobby(player);
    }
}

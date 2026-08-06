package com.theosfera.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LobbyCommandTest {

    @Test
    void rejectsNonPlayerSource() {
        LobbyTransferService transferService =
                mock(LobbyTransferService.class);

        LobbyCommand command =
                new LobbyCommand(transferService);

        CommandSource source =
                mock(CommandSource.class);

        SimpleCommand.Invocation invocation =
                mock(SimpleCommand.Invocation.class);

        when(invocation.source())
                .thenReturn(source);

        command.execute(invocation);

        verify(source).sendMessage(
                LobbyCommand.PLAYER_ONLY_MESSAGE
        );

        verifyNoInteractions(transferService);
    }

    @Test
    void delegatesPlayersWithoutArgumentsToLobbyTransfer() {
        LobbyTransferService transferService =
                mock(LobbyTransferService.class);

        LobbyCommand command =
                new LobbyCommand(transferService);

        Player player =
                mock(Player.class);

        SimpleCommand.Invocation invocation =
                mock(SimpleCommand.Invocation.class);

        when(invocation.source())
                .thenReturn(player);
        when(invocation.arguments())
                .thenReturn(new String[0]);

        command.execute(invocation);

        verify(transferService).transferToLobby(player);
    }

    @Test
    void delegatesSwitchSubcommandToInstanceSwitching() {
        LobbyTransferService transferService =
                mock(LobbyTransferService.class);

        LobbyCommand command =
                new LobbyCommand(transferService);

        Player player =
                mock(Player.class);

        SimpleCommand.Invocation invocation =
                mock(SimpleCommand.Invocation.class);

        when(invocation.source())
                .thenReturn(player);
        when(invocation.arguments())
                .thenReturn(new String[]{"SwItCh"});

        command.execute(invocation);

        verify(transferService).switchLobbyInstance(player);
    }

    @Test
    void rejectsDirectBackendSelection() {
        LobbyTransferService transferService =
                mock(LobbyTransferService.class);

        LobbyCommand command =
                new LobbyCommand(transferService);

        Player player =
                mock(Player.class);

        SimpleCommand.Invocation invocation =
                mock(SimpleCommand.Invocation.class);

        when(invocation.source())
                .thenReturn(player);
        when(invocation.arguments())
                .thenReturn(new String[]{"lobby-2"});

        command.execute(invocation);

        verify(player).sendMessage(LobbyCommand.USAGE_MESSAGE);
        verifyNoInteractions(transferService);
    }

    @Test
    void rejectsExtraSwitchArguments() {
        LobbyTransferService transferService =
                mock(LobbyTransferService.class);

        LobbyCommand command =
                new LobbyCommand(transferService);

        Player player =
                mock(Player.class);

        SimpleCommand.Invocation invocation =
                mock(SimpleCommand.Invocation.class);

        when(invocation.source())
                .thenReturn(player);
        when(invocation.arguments())
                .thenReturn(new String[]{"switch", "lobby-2"});

        command.execute(invocation);

        verify(player).sendMessage(LobbyCommand.USAGE_MESSAGE);
        verifyNoInteractions(transferService);
    }
}

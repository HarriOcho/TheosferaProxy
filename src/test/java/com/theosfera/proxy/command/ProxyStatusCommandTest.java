package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.observability.BackendOperationalSnapshot;
import com.theosfera.proxy.observability.BackendOperationalSnapshotService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProxyStatusCommandTest {

    @Test
    void requiresAdministrativePermission() {
        BackendOperationalSnapshotService snapshotService =
                mock(BackendOperationalSnapshotService.class);

        ProxyStatusCommand command =
                new ProxyStatusCommand(snapshotService);

        CommandSource source =
                mock(CommandSource.class);

        SimpleCommand.Invocation invocation =
                invocation(
                        source,
                        "status"
                );

        when(source.hasPermission(
                ProxyStatusCommand.PERMISSION
        )).thenReturn(true);

        assertTrue(command.hasPermission(invocation));

        verify(source).hasPermission(
                ProxyStatusCommand.PERMISSION
        );
        verifyNoInteractions(snapshotService);
    }

    @Test
    void rejectsSourceWithoutAdministrativePermission() {
        BackendOperationalSnapshotService snapshotService =
                mock(BackendOperationalSnapshotService.class);

        ProxyStatusCommand command =
                new ProxyStatusCommand(snapshotService);

        CommandSource source =
                mock(CommandSource.class);

        SimpleCommand.Invocation invocation =
                invocation(
                        source,
                        "status"
                );

        when(source.hasPermission(
                ProxyStatusCommand.PERMISSION
        )).thenReturn(false);

        assertFalse(command.hasPermission(invocation));

        verify(source).hasPermission(
                ProxyStatusCommand.PERMISSION
        );
        verifyNoInteractions(snapshotService);
    }

    @Test
    void showsUsageForUnknownSubcommand() {
        BackendOperationalSnapshotService snapshotService =
                mock(BackendOperationalSnapshotService.class);

        ProxyStatusCommand command =
                new ProxyStatusCommand(snapshotService);

        CommandSource source =
                mock(CommandSource.class);

        command.execute(
                invocation(
                        source,
                        "unknown"
                )
        );

        verify(source).sendMessage(
                ProxyStatusCommand.USAGE_MESSAGE
        );
        verifyNoInteractions(snapshotService);
    }

    @Test
    void rendersOperationalBackendState() {
        BackendOperationalSnapshotService snapshotService =
                mock(BackendOperationalSnapshotService.class);

        BackendOperationalSnapshot snapshot =
                new BackendOperationalSnapshot(
                        "lobby-1",
                        BackendType.LOBBY,
                        100,
                        90,
                        true,
                        true,
                        BackendHealthStatus.HEALTHY,
                        Optional.of(
                                Instant.parse(
                                        "2026-07-25T04:00:00Z"
                                )
                        ),
                        2,
                        1,
                        false
                );

        when(snapshotService.capture())
                .thenReturn(List.of(snapshot));

        ProxyStatusCommand command =
                new ProxyStatusCommand(snapshotService);

        CommandSource source =
                mock(CommandSource.class);

        command.execute(
                invocation(
                        source,
                        "STATUS"
                )
        );

        verify(source).sendMessage(
                ProxyStatusCommand.HEADER_MESSAGE
        );

        verify(source).sendMessage(
                Component.text(
                        "lobby-1 [LOBBY] velocity=SI auth=SI "
                                + "salud=HEALTHY carga=3/100 "
                                + "(2 conectados + 1 reservados) "
                                + "bootstrapRegistro=NO preferencia=90 "
                                + "ultimaSalud="
                                + "2026-07-25T04:00:00Z"
                )
        );
    }

    @Test
    void reportsEmptyAuthorizedBackendPolicy() {
        BackendOperationalSnapshotService snapshotService =
                mock(BackendOperationalSnapshotService.class);

        when(snapshotService.capture())
                .thenReturn(List.of());

        ProxyStatusCommand command =
                new ProxyStatusCommand(snapshotService);

        CommandSource source =
                mock(CommandSource.class);

        command.execute(
                invocation(
                        source,
                        "status"
                )
        );

        verify(source).sendMessage(
                ProxyStatusCommand.HEADER_MESSAGE
        );

        verify(source).sendMessage(
                ProxyStatusCommand.EMPTY_MESSAGE
        );
    }

    private SimpleCommand.Invocation invocation(
            CommandSource source,
            String... arguments
    ) {
        SimpleCommand.Invocation invocation =
                mock(SimpleCommand.Invocation.class);

        when(invocation.source())
                .thenReturn(source);

        when(invocation.arguments())
                .thenReturn(arguments);

        return invocation;
    }
}

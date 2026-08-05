package com.theosfera.proxy.command;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.observability.BackendOperationalSnapshot;
import com.theosfera.proxy.observability.BackendOperationalSnapshotService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    void rendersReadableOperationalBackendState() {
        BackendOperationalSnapshotService snapshotService =
                mock(BackendOperationalSnapshotService.class);

        BackendOperationalSnapshot snapshot =
                snapshot(
                        BackendHealthStatus.HEALTHY,
                        Optional.of(
                                Instant.parse(
                                        "2026-07-25T04:00:00Z"
                                )
                        ),
                        false
                );

        when(snapshotService.capture())
                .thenReturn(List.of(snapshot));

        Clock clock = Clock.fixed(
                Instant.parse(
                        "2026-07-25T04:00:30Z"
                ),
                ZoneOffset.UTC
        );

        ProxyStatusCommand command =
                new ProxyStatusCommand(
                        snapshotService,
                        clock
                );

        CommandSource source =
                mock(CommandSource.class);

        command.execute(
                invocation(
                        source,
                        "STATUS"
                )
        );

        ArgumentCaptor<Component> messages =
                ArgumentCaptor.forClass(Component.class);

        verify(source, times(3))
                .sendMessage(messages.capture());

        assertEquals(
                List.of(
                        "✦ THEOSFERA PROXY",
                        "Estado operacional de la red",
                        "● lobby-1 [LOBBY] — HEALTHY"
                                + "\n  Velocity: Sí"
                                + "  |  Autenticado: Sí"
                                + "\n  Capacidad: 100"
                                + "  |  Conectados en este proxy: 2"
                                + "\n  Preferencia: 90"
                                + "  |  Última salud: hace 30 s"
                ),
                plainText(messages.getAllValues())
        );
    }

    @Test
    void displaysBootstrapOnlyWhenRegistryEntryExists() {
        BackendOperationalSnapshotService snapshotService =
                mock(BackendOperationalSnapshotService.class);

        when(snapshotService.capture())
                .thenReturn(
                        List.of(
                                snapshot(
                                        BackendHealthStatus.STALE,
                                        Optional.empty(),
                                        true
                                )
                        )
                );

        Clock clock = Clock.fixed(
                Instant.parse(
                        "2026-07-25T04:00:30Z"
                ),
                ZoneOffset.UTC
        );

        ProxyStatusCommand command =
                new ProxyStatusCommand(
                        snapshotService,
                        clock
                );

        CommandSource source =
                mock(CommandSource.class);

        command.execute(
                invocation(
                        source,
                        "status"
                )
        );

        ArgumentCaptor<Component> messages =
                ArgumentCaptor.forClass(Component.class);

        verify(source, times(3))
                .sendMessage(messages.capture());

        assertTrue(
                plainText(
                        messages
                                .getAllValues()
                                .getLast()
                ).endsWith(
                        "\n  Bootstrap: registro presente"
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
                ProxyStatusCommand.SUBHEADER_MESSAGE
        );
        verify(source).sendMessage(
                ProxyStatusCommand.EMPTY_MESSAGE
        );
    }

    @Test
    void formatsRelativeHealthActivity() {
        Instant capturedAt =
                Instant.parse(
                        "2026-07-25T12:00:00Z"
                );

        assertEquals(
                "nunca",
                ProxyStatusCommand
                        .formatLastHealthyActivity(
                                Optional.empty(),
                                capturedAt
                        )
        );

        assertEquals(
                "ahora",
                ProxyStatusCommand
                        .formatLastHealthyActivity(
                                Optional.of(capturedAt),
                                capturedAt
                        )
        );

        assertEquals(
                "hace 59 s",
                ProxyStatusCommand
                        .formatLastHealthyActivity(
                                Optional.of(
                                        capturedAt.minusSeconds(59)
                                ),
                                capturedAt
                        )
        );

        assertEquals(
                "hace 2 min",
                ProxyStatusCommand
                        .formatLastHealthyActivity(
                                Optional.of(
                                        capturedAt.minusSeconds(120)
                                ),
                                capturedAt
                        )
        );

        assertEquals(
                "hace 3 h",
                ProxyStatusCommand
                        .formatLastHealthyActivity(
                                Optional.of(
                                        capturedAt.minusSeconds(
                                                3 * 60 * 60
                                        )
                                ),
                                capturedAt
                        )
        );

        assertEquals(
                "hace 4 d",
                ProxyStatusCommand
                        .formatLastHealthyActivity(
                                Optional.of(
                                        capturedAt.minusSeconds(
                                                4 * 24 * 60 * 60
                                        )
                                ),
                                capturedAt
                        )
        );
    }

    @Test
    void usesSemanticHealthColors() {
        assertEquals(
                NamedTextColor.GREEN,
                ProxyStatusCommand.healthColor(
                        BackendHealthStatus.HEALTHY
                )
        );

        assertEquals(
                ProxyStatusCommand.THEOSFERA_AMBER,
                ProxyStatusCommand.healthColor(
                        BackendHealthStatus.STALE
                )
        );

        assertEquals(
                NamedTextColor.GRAY,
                ProxyStatusCommand.healthColor(
                        BackendHealthStatus.UNKNOWN
                )
        );
    }

    private BackendOperationalSnapshot snapshot(
            BackendHealthStatus healthStatus,
            Optional<Instant> lastHealthyActivity,
            boolean bootstrapReservationPresent
    ) {
        return new BackendOperationalSnapshot(
                "lobby-1",
                BackendType.LOBBY,
                100,
                90,
                true,
                true,
                healthStatus,
                lastHealthyActivity,
                2,
                bootstrapReservationPresent
        );
    }

    private List<String> plainText(
            List<Component> components
    ) {
        return components
                .stream()
                .map(this::plainText)
                .toList();
    }

    private String plainText(Component component) {
        StringBuilder result =
                new StringBuilder();

        appendPlainText(
                component,
                result
        );

        return result.toString();
    }

    private void appendPlainText(
            Component component,
            StringBuilder result
    ) {
        if (component instanceof TextComponent textComponent) {
            result.append(
                    textComponent.content()
            );
        }

        for (Component child : component.children()) {
            appendPlainText(
                    child,
                    result
            );
        }
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

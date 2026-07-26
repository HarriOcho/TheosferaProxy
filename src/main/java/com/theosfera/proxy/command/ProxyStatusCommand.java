package com.theosfera.proxy.command;

import com.theosfera.proxy.observability.BackendOperationalSnapshot;
import com.theosfera.proxy.observability.BackendOperationalSnapshotService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ProxyStatusCommand implements SimpleCommand {

    static final String PERMISSION =
            "theosferaproxy.admin";

    static final Component USAGE_MESSAGE =
            Component.text(
                    "Uso: /theosferaproxy status"
            );

    static final Component HEADER_MESSAGE =
            Component.text(
                    "Estado operacional de TheosferaProxy:"
            );

    static final Component EMPTY_MESSAGE =
            Component.text(
                    "No existen backends autorizados en la política."
            );

    private final BackendOperationalSnapshotService snapshotService;

    public ProxyStatusCommand(
            BackendOperationalSnapshotService snapshotService
    ) {
        this.snapshotService = Objects.requireNonNull(
                snapshotService,
                "snapshotService cannot be null"
        );
    }

    @Override
    public void execute(Invocation invocation) {
        Objects.requireNonNull(
                invocation,
                "invocation cannot be null"
        );

        CommandSource source = invocation.source();
        String[] arguments = invocation.arguments();

        if (arguments.length != 1
                || !arguments[0].equalsIgnoreCase("status")) {
            source.sendMessage(USAGE_MESSAGE);
            return;
        }

        List<BackendOperationalSnapshot> snapshots =
                snapshotService.capture();

        source.sendMessage(HEADER_MESSAGE);

        if (snapshots.isEmpty()) {
            source.sendMessage(EMPTY_MESSAGE);
            return;
        }

        snapshots
                .stream()
                .map(ProxyStatusCommand::formatSnapshot)
                .forEach(source::sendMessage);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        Objects.requireNonNull(
                invocation,
                "invocation cannot be null"
        );

        return invocation
                .source()
                .hasPermission(PERMISSION);
    }

    static Component formatSnapshot(
            BackendOperationalSnapshot snapshot
    ) {
        BackendOperationalSnapshot nonNullSnapshot =
                Objects.requireNonNull(
                        snapshot,
                        "snapshot cannot be null"
                );

        String lastHealthyActivity =
                nonNullSnapshot
                        .lastHealthyActivity()
                        .map(Instant::toString)
                        .orElse("nunca");

        String line = "%s [%s] velocity=%s auth=%s salud=%s "
                + "carga=%d/%d (%d conectados + %d reservados) "
                + "bootstrapRegistro=%s preferencia=%d ultimaSalud=%s";

        return Component.text(
                line.formatted(
                        nonNullSnapshot.serverName(),
                        nonNullSnapshot.backendType(),
                        yesNo(
                                nonNullSnapshot
                                        .registeredInVelocity()
                        ),
                        yesNo(
                                nonNullSnapshot.authenticated()
                        ),
                        nonNullSnapshot.healthStatus(),
                        nonNullSnapshot.allocatedPlayers(),
                        nonNullSnapshot.capacity(),
                        nonNullSnapshot.connectedPlayers(),
                        nonNullSnapshot.reservedCapacity(),
                        yesNo(
                                nonNullSnapshot
                                        .bootstrapReservationPresent()
                        ),
                        nonNullSnapshot.preference(),
                        lastHealthyActivity
                )
        );
    }

    private static String yesNo(boolean value) {
        return value ? "SI" : "NO";
    }
}

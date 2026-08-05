package com.theosfera.proxy.command;

import com.theosfera.proxy.backend.BackendHealthStatus;
import com.theosfera.proxy.observability.BackendOperationalSnapshot;
import com.theosfera.proxy.observability.BackendOperationalSnapshotService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ProxyStatusCommand implements SimpleCommand {

    static final String PERMISSION =
            "theosferaproxy.admin";

    static final TextColor THEOSFERA_GOLD =
            TextColor.color(0xE8B85B);

    static final TextColor THEOSFERA_BRIGHT_GOLD =
            TextColor.color(0xF8E798);

    static final TextColor THEOSFERA_AMBER =
            TextColor.color(0xC46C19);

    static final TextColor THEOSFERA_BRONZE =
            TextColor.color(0x8E5B29);

    static final TextColor THEOSFERA_IVORY =
            TextColor.color(0xF2E4C5);

    static final TextColor THEOSFERA_SECONDARY =
            TextColor.color(0xB89A79);

    static final TextColor THEOSFERA_AQUA =
            TextColor.color(0x55FFFF);

    static final Component USAGE_MESSAGE =
            Component
                    .text(
                            "Uso: ",
                            THEOSFERA_SECONDARY
                    )
                    .append(
                            Component.text(
                                    "/theosferaproxy status",
                                    THEOSFERA_AQUA
                            )
                    );

    static final Component HEADER_MESSAGE =
            Component
                    .text(
                            "✦ ",
                            THEOSFERA_AMBER
                    )
                    .append(
                            Component.text(
                                    "THEOSFERA PROXY",
                                    THEOSFERA_BRIGHT_GOLD,
                                    TextDecoration.BOLD
                            )
                    );

    static final Component SUBHEADER_MESSAGE =
            Component.text(
                    "Estado operacional de la red",
                    THEOSFERA_GOLD
            );

    static final Component EMPTY_MESSAGE =
            Component.text(
                    "No existen backends autorizados en la política.",
                    THEOSFERA_SECONDARY
            );

    private final BackendOperationalSnapshotService snapshotService;
    private final Clock clock;

    public ProxyStatusCommand(
            BackendOperationalSnapshotService snapshotService
    ) {
        this(
                snapshotService,
                Clock.systemUTC()
        );
    }

    ProxyStatusCommand(
            BackendOperationalSnapshotService snapshotService,
            Clock clock
    ) {
        this.snapshotService = Objects.requireNonNull(
                snapshotService,
                "snapshotService cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
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
        source.sendMessage(SUBHEADER_MESSAGE);

        if (snapshots.isEmpty()) {
            source.sendMessage(EMPTY_MESSAGE);
            return;
        }

        Instant capturedAt = clock.instant();

        for (BackendOperationalSnapshot snapshot : snapshots) {
            source.sendMessage(
                    joinLines(
                            formatSnapshot(
                                    snapshot,
                                    capturedAt
                            )
                    )
            );
        }
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

    static List<Component> formatSnapshot(
            BackendOperationalSnapshot snapshot,
            Instant capturedAt
    ) {
        BackendOperationalSnapshot nonNullSnapshot =
                Objects.requireNonNull(
                        snapshot,
                        "snapshot cannot be null"
                );
        Instant nonNullCapturedAt = Objects.requireNonNull(
                capturedAt,
                "capturedAt cannot be null"
        );

        BackendHealthStatus healthStatus =
                nonNullSnapshot.healthStatus();

        TextColor statusColor =
                healthColor(healthStatus);

        List<Component> lines =
                new ArrayList<>(5);

        lines.add(
                Component
                        .text(
                                healthSymbol(healthStatus) + " ",
                                statusColor
                        )
                        .append(
                                Component.text(
                                        nonNullSnapshot.serverName(),
                                        THEOSFERA_AQUA,
                                        TextDecoration.BOLD
                                )
                        )
                        .append(Component.space())
                        .append(
                                Component.text(
                                        "["
                                                + nonNullSnapshot
                                                        .backendType()
                                                + "]",
                                        THEOSFERA_BRONZE
                                )
                        )
                        .append(
                                Component.text(
                                        " — ",
                                        THEOSFERA_SECONDARY
                                )
                        )
                        .append(
                                Component.text(
                                        healthStatus.name(),
                                        statusColor,
                                        TextDecoration.BOLD
                                )
                        )
        );

        lines.add(
                indent()
                        .append(label("Velocity: "))
                        .append(
                                value(
                                        yesNo(
                                                nonNullSnapshot
                                                        .registeredInVelocity()
                                        )
                                )
                        )
                        .append(separator())
                        .append(label("Autenticado: "))
                        .append(
                                value(
                                        yesNo(
                                                nonNullSnapshot
                                                        .authenticated()
                                        )
                                )
                        )
        );

        lines.add(
                indent()
                        .append(label("Capacidad: "))
                        .append(
                                value(
                                        Integer.toString(
                                                nonNullSnapshot.capacity()
                                        )
                                )
                        )
                        .append(separator())
                        .append(label("Conectados en este proxy: "))
                        .append(
                                value(
                                        Integer.toString(
                                                nonNullSnapshot
                                                        .connectedPlayers()
                                        )
                                )
                        )
        );

        lines.add(
                indent()
                        .append(label("Preferencia: "))
                        .append(
                                value(
                                        Integer.toString(
                                                nonNullSnapshot
                                                        .preference()
                                        )
                                )
                        )
                        .append(separator())
                        .append(label("Última salud: "))
                        .append(
                                value(
                                        formatLastHealthyActivity(
                                                nonNullSnapshot
                                                        .lastHealthyActivity(),
                                                nonNullCapturedAt
                                        )
                                )
                        )
        );

        if (nonNullSnapshot.bootstrapReservationPresent()) {
            lines.add(
                    indent()
                            .append(label("Bootstrap: "))
                            .append(
                                    Component.text(
                                            "registro presente",
                                            THEOSFERA_AMBER
                                    )
                            )
            );
        }

        return List.copyOf(lines);
    }

    static String formatLastHealthyActivity(
            Optional<Instant> lastHealthyActivity,
            Instant capturedAt
    ) {
        Optional<Instant> nonNullActivity =
                Objects.requireNonNull(
                        lastHealthyActivity,
                        "lastHealthyActivity cannot be null"
                );
        Instant nonNullCapturedAt = Objects.requireNonNull(
                capturedAt,
                "capturedAt cannot be null"
        );

        if (nonNullActivity.isEmpty()) {
            return "nunca";
        }

        long seconds = Duration
                .between(
                        nonNullActivity.orElseThrow(),
                        nonNullCapturedAt
                )
                .getSeconds();

        if (seconds <= 0) {
            return "ahora";
        }

        if (seconds < 60) {
            return "hace " + seconds + " s";
        }

        long minutes = seconds / 60;

        if (minutes < 60) {
            return "hace " + minutes + " min";
        }

        long hours = minutes / 60;

        if (hours < 24) {
            return "hace " + hours + " h";
        }

        long days = hours / 24;

        return "hace " + days + " d";
    }

    static TextColor healthColor(
            BackendHealthStatus healthStatus
    ) {
        return switch (Objects.requireNonNull(
                healthStatus,
                "healthStatus cannot be null"
        )) {
            case HEALTHY -> NamedTextColor.GREEN;
            case STALE -> THEOSFERA_AMBER;
            case UNKNOWN -> NamedTextColor.GRAY;
        };
    }

    private static String healthSymbol(
            BackendHealthStatus healthStatus
    ) {
        return healthStatus == BackendHealthStatus.UNKNOWN
                ? "○"
                : "●";
    }

    private static Component joinLines(
            List<Component> lines
    ) {
        List<Component> nonNullLines =
                List.copyOf(
                        Objects.requireNonNull(
                                lines,
                                "lines cannot be null"
                        )
                );

        Component result = Component.empty();

        for (int index = 0;
             index < nonNullLines.size();
             index++) {
            if (index > 0) {
                result = result.append(
                        Component.newline()
                );
            }

            result = result.append(
                    nonNullLines.get(index)
            );
        }

        return result;
    }

    private static Component indent() {
        return Component.text("  ");
    }

    private static Component label(String text) {
        return Component.text(
                text,
                THEOSFERA_SECONDARY
        );
    }

    private static Component value(String text) {
        return Component.text(
                text,
                THEOSFERA_IVORY
        );
    }

    private static Component separator() {
        return Component.text(
                "  |  ",
                THEOSFERA_BRONZE
        );
    }

    private static String yesNo(boolean value) {
        return value ? "Sí" : "No";
    }
}

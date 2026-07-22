package com.theosfera.proxy.failover;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.Optional;

public record BackendKickFailoverResolution(
        BackendKickFailoverResolutionStatus status,
        RegisteredServer target,
        Component disconnectReason
) {

    public BackendKickFailoverResolution {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        if (status == BackendKickFailoverResolutionStatus.REDIRECT) {
            Objects.requireNonNull(
                    target,
                    "target is required for a redirect resolution"
            );

            if (disconnectReason != null) {
                throw new IllegalArgumentException(
                        "disconnectReason must be null for a redirect resolution"
                );
            }
        } else if (status
                == BackendKickFailoverResolutionStatus.DISCONNECT) {
            Objects.requireNonNull(
                    disconnectReason,
                    "disconnectReason is required for a disconnect resolution"
            );

            if (target != null) {
                throw new IllegalArgumentException(
                        "target must be null for a disconnect resolution"
                );
            }
        } else if (target != null || disconnectReason != null) {
            throw new IllegalArgumentException(
                    "ignored resolutions cannot identify a target or reason"
            );
        }
    }

    public static BackendKickFailoverResolution ignored() {
        return new BackendKickFailoverResolution(
                BackendKickFailoverResolutionStatus.IGNORED,
                null,
                null
        );
    }

    public static BackendKickFailoverResolution redirect(
            RegisteredServer target
    ) {
        return new BackendKickFailoverResolution(
                BackendKickFailoverResolutionStatus.REDIRECT,
                Objects.requireNonNull(
                        target,
                        "target cannot be null"
                ),
                null
        );
    }

    public static BackendKickFailoverResolution disconnect(
            Component reason
    ) {
        return new BackendKickFailoverResolution(
                BackendKickFailoverResolutionStatus.DISCONNECT,
                null,
                Objects.requireNonNull(
                        reason,
                        "reason cannot be null"
                )
        );
    }

    public Optional<RegisteredServer> redirectTarget() {
        return Optional.ofNullable(target);
    }

    public Optional<Component> reason() {
        return Optional.ofNullable(disconnectReason);
    }
}
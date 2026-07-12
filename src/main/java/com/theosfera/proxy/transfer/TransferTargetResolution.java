package com.theosfera.proxy.transfer;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Objects;
import java.util.Optional;

public record TransferTargetResolution(
        TransferTargetResolutionStatus status,
        RegisteredServer target
) {

    public TransferTargetResolution {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        if (status == TransferTargetResolutionStatus.RESOLVED
                && target == null) {
            throw new IllegalArgumentException(
                    "target is required when status is RESOLVED"
            );
        }

        if (status != TransferTargetResolutionStatus.RESOLVED
                && target != null) {
            throw new IllegalArgumentException(
                    "target must be null when status is not RESOLVED"
            );
        }
    }

    public static TransferTargetResolution resolved(
            RegisteredServer target
    ) {
        return new TransferTargetResolution(
                TransferTargetResolutionStatus.RESOLVED,
                Objects.requireNonNull(
                        target,
                        "target cannot be null"
                )
        );
    }

    public static TransferTargetResolution notConfigured() {
        return new TransferTargetResolution(
                TransferTargetResolutionStatus.NOT_CONFIGURED,
                null
        );
    }

    public static TransferTargetResolution notAuthenticated() {
        return new TransferTargetResolution(
                TransferTargetResolutionStatus.NOT_AUTHENTICATED,
                null
        );
    }

    public Optional<RegisteredServer> resolvedTarget() {
        return Optional.ofNullable(target);
    }
}

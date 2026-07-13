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

        boolean requiresTarget =
                status == TransferTargetResolutionStatus.RESOLVED
                        || status
                        == TransferTargetResolutionStatus
                        .BOOTSTRAP_REQUIRED;

        if (requiresTarget && target == null) {
            throw new IllegalArgumentException(
                    "target is required for a resolved "
                            + "or bootstrap resolution"
            );
        }

        if (!requiresTarget && target != null) {
            throw new IllegalArgumentException(
                    "target must be null when the resolution "
                            + "does not identify a destination"
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

    public static TransferTargetResolution bootstrapRequired(
            RegisteredServer target
    ) {
        return new TransferTargetResolution(
                TransferTargetResolutionStatus.BOOTSTRAP_REQUIRED,
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

    public boolean requiresBootstrap() {
        return status
                == TransferTargetResolutionStatus
                .BOOTSTRAP_REQUIRED;
    }
}

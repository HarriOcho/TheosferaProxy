package com.theosfera.proxy.transfer;

import java.util.List;
import java.util.Objects;

public record TransferTargetCandidates(
        boolean configured,
        List<BackendTargetCandidate> activeCandidates,
        List<BackendTargetCandidate> coldCandidates
) {

    public TransferTargetCandidates {
        activeCandidates = List.copyOf(
                Objects.requireNonNull(
                        activeCandidates,
                        "activeCandidates cannot be null"
                )
        );
        coldCandidates = List.copyOf(
                Objects.requireNonNull(
                        coldCandidates,
                        "coldCandidates cannot be null"
                )
        );

        if (!configured
                && (!activeCandidates.isEmpty()
                || !coldCandidates.isEmpty())) {
            throw new IllegalArgumentException(
                    "unconfigured targets cannot contain candidates"
            );
        }
    }

    public static TransferTargetCandidates notConfigured() {
        return new TransferTargetCandidates(
                false,
                List.of(),
                List.of()
        );
    }

    public static TransferTargetCandidates configured(
            List<BackendTargetCandidate> activeCandidates,
            List<BackendTargetCandidate> coldCandidates
    ) {
        return new TransferTargetCandidates(
                true,
                activeCandidates,
                coldCandidates
        );
    }
}

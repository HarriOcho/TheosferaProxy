package com.theosfera.proxy.transfer;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BackendLoadSelector {

    public Optional<RegisteredServer> select(
            List<BackendLoadCandidate> candidates
    ) {
        List<BackendLoadCandidate> snapshot =
                List.copyOf(
                        Objects.requireNonNull(
                                candidates,
                                "candidates cannot be null"
                        )
                );

        return snapshot
                .stream()
                .filter(
                        BackendLoadCandidate
                                ::hasAvailableCapacity
                )
                .min(BackendLoadSelector::compareCandidates)
                .map(BackendLoadCandidate::server);
    }

    private static int compareCandidates(
            BackendLoadCandidate first,
            BackendLoadCandidate second
    ) {
        long firstWeightedLoad =
                (long) first.connectedPlayers()
                        * second.policyEntry().capacity();

        long secondWeightedLoad =
                (long) second.connectedPlayers()
                        * first.policyEntry().capacity();

        int loadComparison =
                Long.compare(
                        firstWeightedLoad,
                        secondWeightedLoad
                );

        if (loadComparison != 0) {
            return loadComparison;
        }

        int preferenceComparison =
                Integer.compare(
                        second.policyEntry().preference(),
                        first.policyEntry().preference()
                );

        if (preferenceComparison != 0) {
            return preferenceComparison;
        }

        return first.serverName()
                .compareTo(second.serverName());
    }
}

package com.theosfera.proxy.session;

import com.theosfera.proxy.coordination.PlayerSessionLease;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PlayerSessionReleaseTimeoutScheduler {

    ScheduledReleaseTimeout schedule(
            ReleaseTimeoutKey key,
            Runnable timeout
    );

    record ReleaseTimeoutKey(
            UUID playerId,
            PlayerSessionLease lease,
            long fencingToken,
            CompletionStage<Boolean> externalCompletion
    ) {

        public ReleaseTimeoutKey {
            playerId = Objects.requireNonNull(
                    playerId,
                    "playerId cannot be null"
            );

            lease = Objects.requireNonNull(
                    lease,
                    "lease cannot be null"
            );

            externalCompletion = Objects.requireNonNull(
                    externalCompletion,
                    "externalCompletion cannot be null"
            );

            if (fencingToken <= 0) {
                throw new IllegalArgumentException(
                        "fencingToken must be greater than zero"
                );
            }

            if (lease.fencingToken() != fencingToken) {
                throw new IllegalArgumentException(
                        "fencingToken must match lease"
                );
            }

            if (!playerId.equals(lease.session().playerId())) {
                throw new IllegalArgumentException(
                        "playerId must match lease session"
                );
            }
        }
    }

    interface ScheduledReleaseTimeout {

        void cancel();
    }
}

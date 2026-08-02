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

    enum ReleaseTimeoutPhase {
        OWNED_RELEASE_TIMEOUT,
        QUARANTINE_RETENTION_TIMEOUT
    }

    final class ReleaseTimeoutKey {

        private final ReleaseTimeoutPhase phase;
        private final UUID playerId;
        private final PlayerSessionLease lease;
        private final long fencingToken;
        private final CompletionStage<Boolean> externalCompletion;

        public ReleaseTimeoutKey(
                UUID playerId,
                PlayerSessionLease lease,
                long fencingToken,
                CompletionStage<Boolean> externalCompletion
        ) {
            this(
                    ReleaseTimeoutPhase.OWNED_RELEASE_TIMEOUT,
                    playerId,
                    lease,
                    fencingToken,
                    externalCompletion
            );
        }

        public ReleaseTimeoutKey(
                ReleaseTimeoutPhase phase,
                UUID playerId,
                PlayerSessionLease lease,
                long fencingToken,
                CompletionStage<Boolean> externalCompletion
        ) {
            this.phase = Objects.requireNonNull(
                    phase,
                    "phase cannot be null"
            );

            this.playerId = Objects.requireNonNull(
                    playerId,
                    "playerId cannot be null"
            );

            this.lease = Objects.requireNonNull(
                    lease,
                    "lease cannot be null"
            );

            this.externalCompletion = Objects.requireNonNull(
                    externalCompletion,
                    "externalCompletion cannot be null"
            );

            this.fencingToken = fencingToken;

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

        public ReleaseTimeoutPhase phase() {
            return phase;
        }

        public UUID playerId() {
            return playerId;
        }

        public PlayerSessionLease lease() {
            return lease;
        }

        public long fencingToken() {
            return fencingToken;
        }

        public CompletionStage<Boolean> externalCompletion() {
            return externalCompletion;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }

            if (!(object instanceof ReleaseTimeoutKey other)) {
                return false;
            }

            return fencingToken == other.fencingToken
                    && phase == other.phase
                    && playerId.equals(other.playerId)
                    && lease.equals(other.lease)
                    && externalCompletion
                    == other.externalCompletion;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    phase,
                    playerId,
                    lease,
                    fencingToken,
                    System.identityHashCode(externalCompletion)
            );
        }
    }

    interface ScheduledReleaseTimeout {

        void cancel();
    }
}

package com.theosfera.proxy.session;

import java.util.Objects;
import java.util.UUID;

public interface PlayerSessionAcquisitionTimeoutScheduler {

    ScheduledAcquisitionTimeout schedule(
            AcquisitionTimeoutKey key,
            Runnable timeout
    );

    record AcquisitionTimeoutKey(
            UUID playerId,
            UUID requestId,
            long attemptId
    ) {

        public AcquisitionTimeoutKey {
            playerId = Objects.requireNonNull(
                    playerId,
                    "playerId cannot be null"
            );

            requestId = Objects.requireNonNull(
                    requestId,
                    "requestId cannot be null"
            );

            if (attemptId <= 0) {
                throw new IllegalArgumentException(
                        "attemptId must be greater than zero"
                );
            }
        }
    }

    interface ScheduledAcquisitionTimeout {

        void cancel();
    }
}

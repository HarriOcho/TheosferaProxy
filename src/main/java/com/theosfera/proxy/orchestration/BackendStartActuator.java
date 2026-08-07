package com.theosfera.proxy.orchestration;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface BackendStartActuator {

    /**
     * Atomically evaluates the supplied bootstrap authority against the
     * actuator's latest accepted authority for the target and, only when that
     * authority is current, accepts or emits the process-start side effect.
     *
     * <p>The fencing comparison must not be implemented as a separate remote
     * pre-check followed later by an unfenced side effect. A stale request must
     * be unable to start a process after a newer authority has superseded it.</p>
     */
    CompletionStage<BackendStartActuationResult> startIfCurrent(
            BackendStartActuationRequest request
    );
}

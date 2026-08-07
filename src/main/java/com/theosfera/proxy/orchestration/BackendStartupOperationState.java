package com.theosfera.proxy.orchestration;

/**
 * State of the provider-acceptance phase for one backend startup operation.
 *
 * <p>{@link #START_ACCEPTED} means only that the fenced orchestration
 * provider accepted the start instruction. It does not mean the backend is
 * running, authenticated on the Control Channel or HEALTHY.</p>
 */
public enum BackendStartupOperationState {
    NEW,
    STARTING,
    RETRY_WAIT,
    START_ACCEPTED,
    FAILED,
    TIMED_OUT,
    FENCED,
    CANCELLED
}

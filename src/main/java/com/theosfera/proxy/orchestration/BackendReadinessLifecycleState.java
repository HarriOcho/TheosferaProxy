package com.theosfera.proxy.orchestration;

public enum BackendReadinessLifecycleState {
    NEW,
    WAITING_CONTROL,
    WAITING_HEALTH,
    READY,
    FAILED,
    TIMED_OUT,
    FENCED,
    CANCELLED
}

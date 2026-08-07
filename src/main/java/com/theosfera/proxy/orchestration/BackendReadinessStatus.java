package com.theosfera.proxy.orchestration;

public enum BackendReadinessStatus {
    READY,
    TARGET_NOT_CONFIGURED,
    CONTROL_NOT_AUTHENTICATED,
    IDENTITY_MISMATCH,
    HEALTH_NOT_READY
}

package com.theosfera.proxy.session;

public enum PlayerSessionLeaseBindingResult {
    BOUND,
    ALREADY_BOUND,
    REPLACED,
    DISCONNECTED,
    RELEASE_PENDING,
    STALE,
    CONFLICT
}

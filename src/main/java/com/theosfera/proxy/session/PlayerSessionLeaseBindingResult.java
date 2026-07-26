package com.theosfera.proxy.session;

public enum PlayerSessionLeaseBindingResult {
    BOUND,
    ALREADY_BOUND,
    REPLACED,
    DISCONNECTED,
    STALE,
    CONFLICT
}

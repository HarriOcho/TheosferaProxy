package com.theosfera.proxy.session;

public enum PlayerPresenceUpdateResult {
    RECORDED,
    ALREADY_RECORDED,
    UPDATED,
    NOT_AUTHENTICATED,
    STALE,
    CONFLICT
}
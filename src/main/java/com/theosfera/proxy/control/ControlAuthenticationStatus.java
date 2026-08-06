package com.theosfera.proxy.control;

public enum ControlAuthenticationStatus {
    AUTHENTICATED,
    NO_CHALLENGE,
    EXPIRED,
    REQUEST_ID_MISMATCH,
    IDENTITY_REJECTED,
    SECRET_UNAVAILABLE,
    INVALID_PROOF
}

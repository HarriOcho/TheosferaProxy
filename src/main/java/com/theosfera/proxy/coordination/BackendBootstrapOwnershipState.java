package com.theosfera.proxy.coordination;

/**
 * Local lifecycle state for one distributed backend bootstrap ownership.
 */
public enum BackendBootstrapOwnershipState {
    NEW,
    ACQUIRING,
    OWNED,
    DEGRADED,
    STOPPING,
    FENCED,
    STOPPED
}

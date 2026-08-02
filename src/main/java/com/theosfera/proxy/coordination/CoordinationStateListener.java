package com.theosfera.proxy.coordination;

@FunctionalInterface
public interface CoordinationStateListener {

    void onStateChanged(CoordinationState previous, CoordinationState current);
}

package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class CoordinationStateRegistry {

    private final AtomicReference<CoordinationState> state =
            new AtomicReference<>(CoordinationState.STARTING);

    public CoordinationState get() {
        return state.get();
    }

    public void set(CoordinationState next) {
        state.set(Objects.requireNonNull(next, "next cannot be null"));
    }

    public boolean compareAndSet(
            CoordinationState expected,
            CoordinationState next
    ) {
        return state.compareAndSet(
                Objects.requireNonNull(expected, "expected cannot be null"),
                Objects.requireNonNull(next, "next cannot be null")
        );
    }
}

package com.theosfera.proxy.coordination;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class CoordinationStateRegistry {

    private final AtomicReference<CoordinationState> state =
            new AtomicReference<>(CoordinationState.STARTING);
    private final CopyOnWriteArrayList<CoordinationStateListener> listeners =
            new CopyOnWriteArrayList<>();

    public CoordinationState get() {
        return state.get();
    }

    public void set(CoordinationState next) {
        CoordinationState nonNullNext = Objects.requireNonNull(
                next,
                "next cannot be null"
        );
        CoordinationState previous = state.getAndSet(nonNullNext);
        publishIfChanged(previous, nonNullNext);
    }

    public boolean compareAndSet(
            CoordinationState expected,
            CoordinationState next
    ) {
        CoordinationState nonNullExpected = Objects.requireNonNull(
                expected,
                "expected cannot be null"
        );
        CoordinationState nonNullNext = Objects.requireNonNull(
                next,
                "next cannot be null"
        );

        boolean changed = state.compareAndSet(nonNullExpected, nonNullNext);
        if (changed) {
            publishIfChanged(nonNullExpected, nonNullNext);
        }
        return changed;
    }

    public void addListener(CoordinationStateListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener cannot be null"));
    }

    public void removeListener(CoordinationStateListener listener) {
        listeners.remove(Objects.requireNonNull(listener, "listener cannot be null"));
    }

    private void publishIfChanged(
            CoordinationState previous,
            CoordinationState current
    ) {
        if (previous == current) {
            return;
        }
        for (CoordinationStateListener listener : listeners) {
            listener.onStateChanged(previous, current);
        }
    }
}

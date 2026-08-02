package com.theosfera.proxy.coordination;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationStateRegistryTest {

    @Test
    void publishesOnlyRealStateChanges() {
        CoordinationStateRegistry registry = new CoordinationStateRegistry();
        List<String> transitions = new ArrayList<>();
        CoordinationStateListener listener = (previous, current) ->
                transitions.add(previous + "->" + current);
        registry.addListener(listener);

        registry.set(CoordinationState.STARTING);
        registry.set(CoordinationState.HEALTHY);
        registry.set(CoordinationState.HEALTHY);
        registry.set(CoordinationState.DEGRADED);

        assertEquals(
                List.of("STARTING->HEALTHY", "HEALTHY->DEGRADED"),
                transitions
        );

        registry.removeListener(listener);
        registry.set(CoordinationState.FENCED);
        assertEquals(2, transitions.size());
    }

    @Test
    void compareAndSetPublishesOnlyWhenItSucceeds() {
        CoordinationStateRegistry registry = new CoordinationStateRegistry();
        List<String> transitions = new ArrayList<>();
        registry.addListener((previous, current) ->
                transitions.add(previous + "->" + current));

        assertFalse(
                registry.compareAndSet(
                        CoordinationState.HEALTHY,
                        CoordinationState.DEGRADED
                )
        );
        assertTrue(
                registry.compareAndSet(
                        CoordinationState.STARTING,
                        CoordinationState.HEALTHY
                )
        );

        assertEquals(List.of("STARTING->HEALTHY"), transitions);
    }
}

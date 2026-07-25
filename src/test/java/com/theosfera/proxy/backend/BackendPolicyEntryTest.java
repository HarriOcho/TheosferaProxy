package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendPolicyEntryTest {

    @Test
    void storesBackendSelectionConfiguration() {
        BackendPolicyEntry entry =
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        100,
                        90
                );

        assertEquals(
                BackendType.LOBBY,
                entry.backendType()
        );
        assertEquals(100, entry.capacity());
        assertEquals(90, entry.preference());
    }

    @Test
    void rejectsNullBackendType() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendPolicyEntry(
                        null,
                        100,
                        90
                )
        );
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendPolicyEntry(
                        BackendType.LOBBY,
                        0,
                        90
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendPolicyEntry(
                        BackendType.LOBBY,
                        -1,
                        90
                )
        );
    }

    @Test
    void rejectsNegativePreference() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendPolicyEntry(
                        BackendType.LOBBY,
                        100,
                        -1
                )
        );
    }

    @Test
    void acceptsZeroPreference() {
        BackendPolicyEntry entry =
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        100,
                        0
                );

        assertEquals(0, entry.preference());
    }
}

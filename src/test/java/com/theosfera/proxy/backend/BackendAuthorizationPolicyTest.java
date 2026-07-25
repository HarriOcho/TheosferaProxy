package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendHelloPayload;
import com.theosfera.protocol.message.payload.BackendType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendAuthorizationPolicyTest {

    private final BackendAuthorizationPolicy policy =
            new BackendAuthorizationPolicy(
                    Map.of(
                            "auth-1",
                            new BackendPolicyEntry(
                                    BackendType.AUTH,
                                    1,
                                    100
                            ),
                            "lobby-1",
                            new BackendPolicyEntry(
                                    BackendType.LOBBY,
                                    100,
                                    90
                            ),
                            "skyblock-1",
                            new BackendPolicyEntry(
                                    BackendType.SKYBLOCK,
                                    200,
                                    80
                            )
                    )
            );

    @Test
    void authorizesMatchingBackendIdentity() {
        Optional<BackendIdentity> authorized =
                policy.authorize(
                        "lobby-1",
                        new BackendHelloPayload(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                );

        assertTrue(authorized.isPresent());
        assertEquals(
                new BackendIdentity(
                        "lobby-1",
                        BackendType.LOBBY
                ),
                authorized.orElseThrow()
        );
    }

    @Test
    void rejectsMismatchedDeclaredName() {
        Optional<BackendIdentity> authorized =
                policy.authorize(
                        "auth-1",
                        new BackendHelloPayload(
                                "lobby-1",
                                BackendType.AUTH
                        )
                );

        assertTrue(authorized.isEmpty());
    }

    @Test
    void rejectsMismatchedBackendType() {
        Optional<BackendIdentity> authorized =
                policy.authorize(
                        "auth-1",
                        new BackendHelloPayload(
                                "auth-1",
                                BackendType.LOBBY
                        )
                );

        assertTrue(authorized.isEmpty());
    }

    @Test
    void rejectsUnknownBackendName() {
        Optional<BackendIdentity> authorized =
                policy.authorize(
                        "unknown-1",
                        new BackendHelloPayload(
                                "unknown-1",
                                BackendType.LOBBY
                        )
                );

        assertTrue(authorized.isEmpty());
    }

    @Test
    void exposesBackendSelectionConfiguration() {
        assertEquals(
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        100,
                        90
                ),
                policy.backendEntries().get("lobby-1")
        );
    }

    @Test
    void exposesImmutableBackendEntries() {
        Map<String, BackendPolicyEntry> entries =
                policy.backendEntries();

        assertThrows(
                UnsupportedOperationException.class,
                () -> entries.put(
                        "other-1",
                        new BackendPolicyEntry(
                                BackendType.LOBBY,
                                100,
                                90
                        )
                )
        );
    }

    @Test
    void exposesAuthorizedBackendNames() {
        assertEquals(
                java.util.Set.of(
                        "auth-1",
                        "lobby-1",
                        "skyblock-1"
                ),
                policy.authorizedBackendNames()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> policy.authorizedBackendNames()
                        .add("other-1")
        );
    }

    @Test
    void normalizesConfiguredServerNames() {
        BackendAuthorizationPolicy normalized =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "  lobby-1  ",
                                new BackendPolicyEntry(
                                        BackendType.LOBBY,
                                        100,
                                        90
                                )
                        )
                );

        assertTrue(
                normalized.authorize(
                        "lobby-1",
                        new BackendHelloPayload(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                ).isPresent()
        );
    }

    @Test
    void rejectsDuplicateNamesAfterNormalization() {
        Map<String, BackendPolicyEntry> duplicated =
                new LinkedHashMap<>();

        BackendPolicyEntry entry =
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        100,
                        90
                );

        duplicated.put("lobby-1", entry);
        duplicated.put("  lobby-1  ", entry);

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendAuthorizationPolicy(
                        duplicated
                )
        );
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendAuthorizationPolicy(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> policy.authorize(
                        null,
                        new BackendHelloPayload(
                                "lobby-1",
                                BackendType.LOBBY
                        )
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> policy.authorize(
                        "lobby-1",
                        null
                )
        );
    }

    @Test
    void identityRejectsInvalidValues() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendIdentity(
                        null,
                        BackendType.LOBBY
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new BackendIdentity(
                        "lobby-1",
                        null
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendIdentity(
                        "lobby 1",
                        BackendType.LOBBY
                )
        );
    }
}

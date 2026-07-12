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
                            BackendType.AUTH,
                            "lobby-1",
                            BackendType.LOBBY,
                            "skyblock-1",
                            BackendType.SKYBLOCK
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
    void exposesImmutableAllowedBackends() {
        Map<String, BackendType> allowed =
                policy.allowedBackends();

        assertThrows(
                UnsupportedOperationException.class,
                () -> allowed.put(
                        "other-1",
                        BackendType.LOBBY
                )
        );
    }

    @Test
    void normalizesConfiguredServerNames() {
        BackendAuthorizationPolicy normalized =
                new BackendAuthorizationPolicy(
                        Map.of(
                                "  lobby-1  ",
                                BackendType.LOBBY
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
        Map<String, BackendType> duplicated =
                new LinkedHashMap<>();

        duplicated.put(
                "lobby-1",
                BackendType.LOBBY
        );
        duplicated.put(
                "  lobby-1  ",
                BackendType.LOBBY
        );

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
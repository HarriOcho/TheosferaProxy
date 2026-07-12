package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendMessageAuthorizerTest {

    private final BackendIdentityRegistry registry =
            new BackendIdentityRegistry();

    private final BackendMessageAuthorizer authorizer =
            new BackendMessageAuthorizer(registry);

    @Test
    void allowsHelloBeforeRegistration() {
        assertTrue(
                authorizer.isAuthorized(
                        "unknown-1",
                        ProtocolMessageType.BACKEND_HELLO
                )
        );
    }

    @Test
    void rejectsNonHelloMessagesBeforeRegistration() {
        assertFalse(
                authorizer.isAuthorized(
                        "lobby-1",
                        ProtocolMessageType.PING
                )
        );
        assertFalse(
                authorizer.isAuthorized(
                        "auth-1",
                        ProtocolMessageType
                                .PLAYER_AUTHENTICATED
                )
        );
    }

    @Test
    void allowsHeartbeatForEveryRegisteredBackendType() {
        for (BackendType backendType
                : BackendType.values()) {
            String serverName = backendType.name()
                    .toLowerCase()
                    + "-1";

            register(serverName, backendType);

            assertTrue(
                    authorizer.isAuthorized(
                            serverName,
                            ProtocolMessageType.PING
                    )
            );
            assertTrue(
                    authorizer.isAuthorized(
                            serverName,
                            ProtocolMessageType.PONG
                    )
            );
        }
    }

    @Test
    void allowsPlayerAuthenticatedOnlyFromAuth() {
        register("auth-1", BackendType.AUTH);
        register("lobby-1", BackendType.LOBBY);

        assertTrue(
                authorizer.isAuthorized(
                        "auth-1",
                        ProtocolMessageType
                                .PLAYER_AUTHENTICATED
                )
        );
        assertFalse(
                authorizer.isAuthorized(
                        "lobby-1",
                        ProtocolMessageType
                                .PLAYER_AUTHENTICATED
                )
        );
    }

    @Test
    void allowsReadyAndTransferOnlyFromPlayableBackends() {
        register("auth-1", BackendType.AUTH);
        register("lobby-1", BackendType.LOBBY);
        register("skyblock-1", BackendType.SKYBLOCK);

        assertFalse(
                authorizer.isAuthorized(
                        "auth-1",
                        ProtocolMessageType
                                .PLAYER_SERVER_READY
                )
        );
        assertFalse(
                authorizer.isAuthorized(
                        "auth-1",
                        ProtocolMessageType.TRANSFER_REQUEST
                )
        );

        for (String serverName
                : new String[]{"lobby-1", "skyblock-1"}) {
            assertTrue(
                    authorizer.isAuthorized(
                            serverName,
                            ProtocolMessageType
                                    .PLAYER_SERVER_READY
                    )
            );
            assertTrue(
                    authorizer.isAuthorized(
                            serverName,
                            ProtocolMessageType
                                    .TRANSFER_REQUEST
                    )
            );
        }
    }

    @Test
    void rejectsProxyOwnedResponseTypesFromBackends() {
        register("lobby-1", BackendType.LOBBY);

        assertFalse(
                authorizer.isAuthorized(
                        "lobby-1",
                        ProtocolMessageType
                                .BACKEND_HELLO_ACK
                )
        );
        assertFalse(
                authorizer.isAuthorized(
                        "lobby-1",
                        ProtocolMessageType.TRANSFER_RESULT
                )
        );
    }

    @Test
    void rejectsUnknownMessageType() {
        register("lobby-1", BackendType.LOBBY);

        assertFalse(
                authorizer.isAuthorized(
                        "lobby-1",
                        "UNKNOWN_MESSAGE"
                )
        );
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendMessageAuthorizer(null)
        );

        assertThrows(
                NullPointerException.class,
                () -> authorizer.isAuthorized(
                        null,
                        ProtocolMessageType.PING
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> authorizer.isAuthorized(
                        "lobby-1",
                        null
                )
        );
    }

    private void register(
            String serverName,
            BackendType backendType
    ) {
        registry.register(
                new BackendIdentity(
                        serverName,
                        backendType
                )
        );
    }
}
package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendMessageAuthorizerTest {

    private final MutableBackendIdentityProvider identityProvider =
            new MutableBackendIdentityProvider();

    private final BackendMessageAuthorizer authorizer =
            new BackendMessageAuthorizer(identityProvider);

    @Test
    void rejectsPlayerScopedMessagesWithoutLiveControlIdentity() {
        assertFalse(
                authorizer.isAuthorized(
                        "auth-1",
                        ProtocolMessageType.PLAYER_AUTHENTICATED
                )
        );
        assertFalse(
                authorizer.isAuthorized(
                        "lobby-1",
                        ProtocolMessageType.PLAYER_SERVER_READY
                )
        );
        assertFalse(
                authorizer.isAuthorized(
                        "lobby-1",
                        ProtocolMessageType.TRANSFER_REQUEST
                )
        );
    }

    @Test
    void rejectsBackendLevelTrafficOverPluginMessaging() {
        register("lobby-1", BackendType.LOBBY);

        assertFalse(authorizer.isAuthorized("lobby-1", "BACKEND_HELLO"));
        assertFalse(authorizer.isAuthorized("lobby-1", "BACKEND_HELLO_ACK"));
        assertFalse(authorizer.isAuthorized("lobby-1", "PING"));
        assertFalse(authorizer.isAuthorized("lobby-1", "PONG"));
    }

    @Test
    void allowsPlayerAuthenticatedOnlyFromAuth() {
        register("auth-1", BackendType.AUTH);
        register("lobby-1", BackendType.LOBBY);

        assertTrue(
                authorizer.isAuthorized(
                        "auth-1",
                        ProtocolMessageType.PLAYER_AUTHENTICATED
                )
        );
        assertFalse(
                authorizer.isAuthorized(
                        "lobby-1",
                        ProtocolMessageType.PLAYER_AUTHENTICATED
                )
        );
    }

    @Test
    void allowsReadyOnlyFromPlayableBackends() {
        register("auth-1", BackendType.AUTH);
        register("lobby-1", BackendType.LOBBY);
        register("skyblock-1", BackendType.SKYBLOCK);

        assertFalse(
                authorizer.isAuthorized(
                        "auth-1",
                        ProtocolMessageType.PLAYER_SERVER_READY
                )
        );

        for (String serverName
                : new String[]{"lobby-1", "skyblock-1"}) {
            assertTrue(
                    authorizer.isAuthorized(
                            serverName,
                            ProtocolMessageType.PLAYER_SERVER_READY
                    )
            );
        }
    }

    @Test
    void allowsTransferRequestsFromSupportedBackendTypes() {
        register("auth-1", BackendType.AUTH);
        register("lobby-1", BackendType.LOBBY);
        register("skyblock-1", BackendType.SKYBLOCK);

        for (String serverName
                : new String[]{
                "auth-1",
                "lobby-1",
                "skyblock-1"
        }) {
            assertTrue(
                    authorizer.isAuthorized(
                            serverName,
                            ProtocolMessageType.TRANSFER_REQUEST
                    )
            );
        }
    }

    @Test
    void rejectsProxyOwnedResponseTypesFromBackends() {
        register("auth-1", BackendType.AUTH);
        register("lobby-1", BackendType.LOBBY);

        assertFalse(
                authorizer.isAuthorized(
                        "auth-1",
                        ProtocolMessageType.PLAYER_AUTHENTICATED_ACK
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
                        ProtocolMessageType.TRANSFER_REQUEST
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
        identityProvider.register(
                new BackendIdentity(
                        serverName,
                        backendType
                )
        );
    }
}

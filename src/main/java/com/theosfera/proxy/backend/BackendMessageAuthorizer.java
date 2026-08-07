package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;

import java.util.Objects;
import java.util.Optional;

public final class BackendMessageAuthorizer {

    private final BackendIdentityProvider identityProvider;

    public BackendMessageAuthorizer(
            BackendIdentityProvider identityProvider
    ) {
        this.identityProvider = Objects.requireNonNull(
                identityProvider,
                "identityProvider cannot be null"
        );
    }

    public boolean isAuthorized(
            String serverName,
            String messageType
    ) {
        Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        );
        Objects.requireNonNull(
                messageType,
                "messageType cannot be null"
        );

        Optional<BackendIdentity> identity =
                identityProvider.find(serverName);

        if (identity.isEmpty()) {
            return false;
        }

        BackendType backendType =
                identity.orElseThrow().backendType();

        return switch (messageType) {
            case ProtocolMessageType.PLAYER_AUTHENTICATED ->
                    backendType == BackendType.AUTH;

            case ProtocolMessageType.PLAYER_SERVER_READY ->
                    backendType == BackendType.LOBBY
                            || backendType == BackendType.SKYBLOCK;

            case ProtocolMessageType.TRANSFER_REQUEST ->
                    backendType == BackendType.AUTH
                            || backendType == BackendType.LOBBY
                            || backendType == BackendType.SKYBLOCK;

            case ProtocolMessageType.PLAYER_AUTHENTICATED_ACK,
                 ProtocolMessageType.TRANSFER_RESULT -> false;

            default -> false;
        };
    }
}

package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.BackendType;

import java.util.Objects;
import java.util.Optional;

public final class BackendMessageAuthorizer {

    private final BackendIdentityRegistry identityRegistry;

    public BackendMessageAuthorizer(
            BackendIdentityRegistry identityRegistry
    ) {
        this.identityRegistry = Objects.requireNonNull(
                identityRegistry,
                "identityRegistry cannot be null"
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

        if (ProtocolMessageType.BACKEND_HELLO.equals(
                messageType
        )) {
            return true;
        }

        Optional<BackendIdentity> identity =
                identityRegistry.find(serverName);

        if (identity.isEmpty()) {
            return false;
        }

        BackendType backendType =
                identity.orElseThrow().backendType();

        return switch (messageType) {
            case ProtocolMessageType.PING,
                 ProtocolMessageType.PONG -> true;

            case ProtocolMessageType.PLAYER_AUTHENTICATED ->
                    backendType == BackendType.AUTH;

            case ProtocolMessageType.PLAYER_SERVER_READY,
                 ProtocolMessageType.TRANSFER_REQUEST ->
                    backendType == BackendType.LOBBY
                            || backendType
                            == BackendType.SKYBLOCK;

            case ProtocolMessageType.BACKEND_HELLO_ACK,
                 ProtocolMessageType.TRANSFER_RESULT -> false;

            default -> false;
        };
    }
}
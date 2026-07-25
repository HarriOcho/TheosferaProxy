package com.theosfera.proxy.transfer;

import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Objects;

public record BackendLoadCandidate(
        String serverName,
        RegisteredServer server,
        BackendPolicyEntry policyEntry,
        int connectedPlayers,
        int reservedPlayers
) {

    public BackendLoadCandidate(
            String serverName,
            RegisteredServer server,
            BackendPolicyEntry policyEntry,
            int connectedPlayers
    ) {
        this(serverName, server, policyEntry, connectedPlayers, 0);
    }

    public BackendLoadCandidate {
        Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        );
        Objects.requireNonNull(
                server,
                "server cannot be null"
        );
        Objects.requireNonNull(
                policyEntry,
                "policyEntry cannot be null"
        );

        if (serverName.isBlank()) {
            throw new IllegalArgumentException(
                    "serverName cannot be blank"
            );
        }

        if (connectedPlayers < 0) {
            throw new IllegalArgumentException(
                    "connectedPlayers cannot be negative"
            );
        }

        if (reservedPlayers < 0) {
            throw new IllegalArgumentException(
                    "reservedPlayers cannot be negative"
            );
        }
    }

    public long effectivePlayers() {
        return (long) connectedPlayers + reservedPlayers;
    }

    public boolean hasAvailableCapacity() {
        return effectivePlayers() < policyEntry.capacity();
    }
}

package com.theosfera.proxy.transfer;

import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Objects;

public record BackendTargetCandidate(
        String serverName,
        RegisteredServer server,
        BackendPolicyEntry policyEntry
) {

    public BackendTargetCandidate {
        serverName = Objects.requireNonNull(
                serverName,
                "serverName cannot be null"
        );
        server = Objects.requireNonNull(
                server,
                "server cannot be null"
        );
        policyEntry = Objects.requireNonNull(
                policyEntry,
                "policyEntry cannot be null"
        );

        if (serverName.isBlank()) {
            throw new IllegalArgumentException(
                    "serverName cannot be blank"
            );
        }

        if (!serverName.equals(
                server.getServerInfo().getName()
        )) {
            throw new IllegalArgumentException(
                    "serverName must match registered server"
            );
        }
    }
}

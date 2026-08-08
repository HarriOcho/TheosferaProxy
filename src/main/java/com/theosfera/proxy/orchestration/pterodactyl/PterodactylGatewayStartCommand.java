package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import com.theosfera.proxy.orchestration.BackendStartActuationRequest;

import java.util.Objects;
import java.util.UUID;

public record PterodactylGatewayStartCommand(
        String backendName,
        String pterodactylTarget,
        UUID requestId,
        UUID playerId,
        String proxyName,
        UUID proxyIncarnationId,
        long membershipFencingToken,
        long bootstrapFencingToken
) {

    public PterodactylGatewayStartCommand {
        backendName = requireText(backendName, "backendName");
        pterodactylTarget = requireText(
                pterodactylTarget,
                "pterodactylTarget"
        );
        Objects.requireNonNull(requestId, "requestId cannot be null");
        Objects.requireNonNull(playerId, "playerId cannot be null");
        proxyName = requireText(proxyName, "proxyName");
        Objects.requireNonNull(
                proxyIncarnationId,
                "proxyIncarnationId cannot be null"
        );
        if (membershipFencingToken <= 0L) {
            throw new IllegalArgumentException(
                    "membershipFencingToken must be greater than zero"
            );
        }
        if (bootstrapFencingToken <= 0L) {
            throw new IllegalArgumentException(
                    "bootstrapFencingToken must be greater than zero"
            );
        }
    }

    public static PterodactylGatewayStartCommand from(
            BackendStartActuationRequest request
    ) {
        BackendStartActuationRequest nonNullRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        BackendBootstrapLease lease = nonNullRequest.bootstrapLease();
        ProxyMembershipLease membership = lease.ownerMembership();
        ProxyInstanceIdentity proxy = membership.owner();

        return new PterodactylGatewayStartCommand(
                nonNullRequest.target().backendName(),
                nonNullRequest.target().targetReference(),
                lease.requestId(),
                lease.playerId(),
                proxy.proxyName(),
                proxy.incarnationId(),
                membership.fencingToken(),
                lease.fencingToken()
        );
    }

    public String toJson() {
        return "{" +
                "\"backendName\":\"" + escapeJson(backendName) + "\"," +
                "\"pterodactylTarget\":\"" + escapeJson(pterodactylTarget) + "\"," +
                "\"requestId\":\"" + requestId + "\"," +
                "\"playerId\":\"" + playerId + "\"," +
                "\"proxyName\":\"" + escapeJson(proxyName) + "\"," +
                "\"proxyIncarnationId\":\"" + proxyIncarnationId + "\"," +
                "\"membershipFencingToken\":" + membershipFencingToken + "," +
                "\"bootstrapFencingToken\":" + bootstrapFencingToken +
                "}";
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(
                value,
                name + " cannot be null"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }
}

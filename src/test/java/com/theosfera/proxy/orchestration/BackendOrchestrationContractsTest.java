package com.theosfera.proxy.orchestration;

import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendOrchestrationContractsTest {

    @Test
    void startRequestRetainsExactBootstrapLease() {
        BackendBootstrapLease lease = bootstrapLease();

        BackendStartRequest request = new BackendStartRequest(lease);

        assertSame(lease, request.bootstrapLease());
    }

    @Test
    void startRequestRejectsMissingBootstrapAuthority() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendStartRequest(null)
        );
    }

    @Test
    void acceptedResultIsTheOnlyAcceptedStatus() {
        assertTrue(BackendStartResult.accepted().isAccepted());

        for (BackendStartResult.Status status
                : BackendStartResult.Status.values()) {
            if (status == BackendStartResult.Status.ACCEPTED) {
                continue;
            }

            assertFalse(
                    BackendStartResult.of(status).isAccepted(),
                    () -> status + " must not be treated as accepted"
            );
        }
    }

    @Test
    void startResultRejectsMissingStatus() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendStartResult(null)
        );
    }

    private static BackendBootstrapLease bootstrapLease() {
        UUID incarnationId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );
        UUID requestId = UUID.fromString(
                "00000000-0000-0000-0000-000000000002"
        );
        UUID playerId = UUID.fromString(
                "00000000-0000-0000-0000-000000000003"
        );

        ProxyMembershipLease membershipLease =
                new ProxyMembershipLease(
                        new ProxyInstanceIdentity(
                                "proxy-1",
                                incarnationId
                        ),
                        7L
                );

        return new BackendBootstrapLease(
                "lobby-2",
                requestId,
                playerId,
                membershipLease,
                41L
        );
    }
}

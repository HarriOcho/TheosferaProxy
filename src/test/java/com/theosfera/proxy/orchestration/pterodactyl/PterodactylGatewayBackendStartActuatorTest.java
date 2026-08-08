package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.proxy.coordination.BackendBootstrapLease;
import com.theosfera.proxy.coordination.ProxyInstanceIdentity;
import com.theosfera.proxy.coordination.ProxyMembershipLease;
import com.theosfera.proxy.orchestration.BackendStartActuationRequest;
import com.theosfera.proxy.orchestration.BackendStartActuationResult;
import com.theosfera.proxy.orchestration.BackendStartTarget;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PterodactylGatewayBackendStartActuatorTest {

    @Test
    void acceptedResponsePreservesExactFencingIdentity() {
        FakeTransport transport = new FakeTransport(
                new PterodactylGatewayTransport.Response(202, "ACCEPTED")
        );
        PterodactylGatewayBackendStartActuator actuator =
                new PterodactylGatewayBackendStartActuator(transport);

        BackendStartActuationResult result = actuator
                .startIfCurrent(request())
                .toCompletableFuture()
                .join();

        assertEquals(
                BackendStartActuationResult.Status.ACCEPTED,
                result.status()
        );
        PterodactylGatewayStartCommand command = transport.command;
        assertNotNull(command);
        assertEquals("lobby-2", command.backendName());
        assertEquals("ptero-server-42", command.pterodactylTarget());
        assertEquals(REQUEST_ID, command.requestId());
        assertEquals(PLAYER_ID, command.playerId());
        assertEquals("proxy-1", command.proxyName());
        assertEquals(PROXY_INCARNATION, command.proxyIncarnationId());
        assertEquals(7L, command.membershipFencingToken());
        assertEquals(41L, command.bootstrapFencingToken());

        String json = command.toJson();
        assertTrue(json.contains("\"membershipFencingToken\":7"));
        assertTrue(json.contains("\"bootstrapFencingToken\":41"));
    }

    @Test
    void mapsSemanticGatewayResults() {
        assertStatus(
                "STALE_AUTHORITY",
                BackendStartActuationResult.Status.STALE_AUTHORITY
        );
        assertStatus(
                "CONFLICT",
                BackendStartActuationResult.Status.CONFLICT
        );
        assertStatus(
                "REJECTED",
                BackendStartActuationResult.Status.REJECTED
        );
    }

    @Test
    void networkAndRetryableHttpFailuresAreUnavailable() {
        assertHttpStatus(
                408,
                BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE
        );
        assertHttpStatus(
                425,
                BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE
        );
        assertHttpStatus(
                429,
                BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE
        );
        assertHttpStatus(
                503,
                BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE
        );

        PterodactylGatewayBackendStartActuator actuator =
                new PterodactylGatewayBackendStartActuator(
                        command -> CompletableFuture.failedFuture(
                                new RuntimeException("gateway offline")
                        )
                );

        assertEquals(
                BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE,
                actuator.startIfCurrent(request())
                        .toCompletableFuture()
                        .join()
                        .status()
        );
    }

    @Test
    void malformedSuccessAndNonRetryableHttpFailuresRejectFailClosed() {
        PterodactylGatewayBackendStartActuator malformed =
                actuator(200, "SOMETHING_ELSE");
        assertEquals(
                BackendStartActuationResult.Status.REJECTED,
                malformed.startIfCurrent(request())
                        .toCompletableFuture()
                        .join()
                        .status()
        );

        assertHttpStatus(
                302,
                BackendStartActuationResult.Status.REJECTED
        );
        assertHttpStatus(
                401,
                BackendStartActuationResult.Status.REJECTED
        );
        assertHttpStatus(
                403,
                BackendStartActuationResult.Status.REJECTED
        );
        assertHttpStatus(
                409,
                BackendStartActuationResult.Status.REJECTED
        );
    }

    private static void assertStatus(
            String gatewayStatus,
            BackendStartActuationResult.Status expected
    ) {
        PterodactylGatewayBackendStartActuator actuator =
                actuator(200, gatewayStatus);
        assertEquals(
                expected,
                actuator.startIfCurrent(request())
                        .toCompletableFuture()
                        .join()
                        .status()
        );
    }

    private static void assertHttpStatus(
            int statusCode,
            BackendStartActuationResult.Status expected
    ) {
        PterodactylGatewayBackendStartActuator actuator =
                actuator(statusCode, "ignored");
        assertEquals(
                expected,
                actuator.startIfCurrent(request())
                        .toCompletableFuture()
                        .join()
                        .status()
        );
    }

    private static PterodactylGatewayBackendStartActuator actuator(
            int statusCode,
            String body
    ) {
        return new PterodactylGatewayBackendStartActuator(
                new FakeTransport(
                        new PterodactylGatewayTransport.Response(
                                statusCode,
                                body
                        )
                )
        );
    }

    private static BackendStartActuationRequest request() {
        return new BackendStartActuationRequest(
                new BackendStartTarget(
                        "lobby-2",
                        "ptero-server-42"
                ),
                new BackendBootstrapLease(
                        "lobby-2",
                        REQUEST_ID,
                        PLAYER_ID,
                        new ProxyMembershipLease(
                                new ProxyInstanceIdentity(
                                        "proxy-1",
                                        PROXY_INCARNATION
                                ),
                                7L
                        ),
                        41L
                )
        );
    }

    private static final UUID REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000002"
    );
    private static final UUID PLAYER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000003"
    );
    private static final UUID PROXY_INCARNATION = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );

    private static final class FakeTransport
            implements PterodactylGatewayTransport {

        private final Response response;
        private PterodactylGatewayStartCommand command;

        private FakeTransport(Response response) {
            this.response = response;
        }

        @Override
        public CompletionStage<Response> start(
                PterodactylGatewayStartCommand command
        ) {
            this.command = command;
            return CompletableFuture.completedFuture(response);
        }
    }
}

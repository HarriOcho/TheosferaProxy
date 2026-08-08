package com.theosfera.proxy.orchestration.pterodactyl;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdkPterodactylGatewayTransportTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void postsToFixedGatewayEndpointWithAuthAndTimeout() {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(202);
        when(response.body()).thenReturn("ACCEPTED");
        doReturn(CompletableFuture.completedFuture(response))
                .when(client)
                .sendAsync(
                        any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class)
                );

        PterodactylGatewayConfig config = new PterodactylGatewayConfig(
                true,
                URI.create("https://orchestration.internal.example:25610"),
                Duration.ofSeconds(5),
                "THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN",
                Map.of("lobby-2", "ptero-server-42")
        );
        JdkPterodactylGatewayTransport transport =
                new JdkPterodactylGatewayTransport(
                        config,
                        "test-gateway-token",
                        client
                );

        PterodactylGatewayTransport.Response result = transport.start(
                new PterodactylGatewayStartCommand(
                        "lobby-2",
                        "ptero-server-42",
                        UUID.fromString(
                                "00000000-0000-0000-0000-000000000002"
                        ),
                        UUID.fromString(
                                "00000000-0000-0000-0000-000000000003"
                        ),
                        "proxy-1",
                        UUID.fromString(
                                "00000000-0000-0000-0000-000000000001"
                        ),
                        7L,
                        41L
                )
        ).toCompletableFuture().join();

        assertEquals(202, result.statusCode());
        assertEquals("ACCEPTED", result.body());

        var requestCaptor = org.mockito.ArgumentCaptor
                .forClass(HttpRequest.class);
        verify(client).sendAsync(
                requestCaptor.capture(),
                any(HttpResponse.BodyHandler.class)
        );

        HttpRequest request = requestCaptor.getValue();
        assertEquals(config.startEndpoint(), request.uri());
        assertEquals("POST", request.method());
        assertEquals(
                Duration.ofSeconds(5),
                request.timeout().orElseThrow()
        );
        assertEquals(
                "Bearer test-gateway-token",
                request.headers().firstValue("Authorization").orElseThrow()
        );
        assertEquals(
                "application/json; charset=utf-8",
                request.headers().firstValue("Content-Type").orElseThrow()
        );
        assertTrue(request.bodyPublisher().isPresent());
    }
}

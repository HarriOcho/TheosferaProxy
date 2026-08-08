package com.theosfera.proxy.orchestration.pterodactyl;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class JdkPterodactylGatewayTransport
        implements PterodactylGatewayTransport {

    private final PterodactylGatewayConfig config;
    private final String authorizationHeader;
    private final HttpClient httpClient;

    public JdkPterodactylGatewayTransport(
            PterodactylGatewayConfig config,
            String gatewayToken
    ) {
        this(
                config,
                gatewayToken,
                HttpClient.newBuilder()
                        .connectTimeout(
                                Objects.requireNonNull(config).requestTimeout()
                        )
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
        );
    }

    JdkPterodactylGatewayTransport(
            PterodactylGatewayConfig config,
            String gatewayToken,
            HttpClient httpClient
    ) {
        this.config = Objects.requireNonNull(
                config,
                "config cannot be null"
        );
        if (!config.enabled()) {
            throw new IllegalArgumentException(
                    "Pterodactyl gateway transport requires enabled config"
            );
        }
        this.authorizationHeader = "Bearer " + requireToken(gatewayToken);
        this.httpClient = Objects.requireNonNull(
                httpClient,
                "httpClient cannot be null"
        );
    }

    @Override
    public CompletionStage<Response> start(
            PterodactylGatewayStartCommand command
    ) {
        PterodactylGatewayStartCommand nonNullCommand =
                Objects.requireNonNull(
                        command,
                        "command cannot be null"
                );

        HttpRequest request = HttpRequest.newBuilder(
                        config.startEndpoint()
                )
                .timeout(config.requestTimeout())
                .header("Authorization", authorizationHeader)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(
                        nonNullCommand.toJson(),
                        StandardCharsets.UTF_8
                ))
                .build();

        return httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                )
                .thenApply(response -> new Response(
                        response.statusCode(),
                        response.body()
                ));
    }

    private static String requireToken(String gatewayToken) {
        String token = Objects.requireNonNull(
                gatewayToken,
                "gatewayToken cannot be null"
        ).trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException(
                    "gatewayToken cannot be blank"
            );
        }
        if (token.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "gatewayToken cannot contain control characters"
            );
        }
        return token;
    }
}

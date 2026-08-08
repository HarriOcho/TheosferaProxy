package com.theosfera.proxy.orchestration.pterodactyl;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public interface PterodactylGatewayTransport {

    CompletionStage<Response> start(
            PterodactylGatewayStartCommand command
    );

    record Response(
            int statusCode,
            String body
    ) {
        public Response {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException(
                        "statusCode must be a valid HTTP status"
                );
            }
            body = Objects.requireNonNull(
                    body,
                    "body cannot be null"
            ).trim();
        }
    }
}

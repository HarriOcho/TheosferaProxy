package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.proxy.orchestration.BackendStartActuationRequest;
import com.theosfera.proxy.orchestration.BackendStartActuationResult;
import com.theosfera.proxy.orchestration.BackendStartActuator;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PterodactylGatewayBackendStartActuator
        implements BackendStartActuator {

    private final PterodactylGatewayTransport transport;

    public PterodactylGatewayBackendStartActuator(
            PterodactylGatewayTransport transport
    ) {
        this.transport = Objects.requireNonNull(
                transport,
                "transport cannot be null"
        );
    }

    @Override
    public CompletionStage<BackendStartActuationResult> startIfCurrent(
            BackendStartActuationRequest request
    ) {
        BackendStartActuationRequest nonNullRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );

        final CompletionStage<PterodactylGatewayTransport.Response> stage;
        try {
            stage = transport.start(
                    PterodactylGatewayStartCommand.from(nonNullRequest)
            );
        } catch (RuntimeException exception) {
            return completed(
                    BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE
            );
        }

        if (stage == null) {
            return completed(
                    BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE
            );
        }

        return stage.handle((response, failure) -> {
            if (failure != null || response == null) {
                return result(
                        BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE
                );
            }
            return map(response);
        });
    }

    private static BackendStartActuationResult map(
            PterodactylGatewayTransport.Response response
    ) {
        int statusCode = response.statusCode();

        if (statusCode >= 200 && statusCode <= 299) {
            String status = response.body()
                    .trim()
                    .toUpperCase(Locale.ROOT);
            return switch (status) {
                case "ACCEPTED" -> result(
                        BackendStartActuationResult.Status.ACCEPTED
                );
                case "STALE_AUTHORITY" -> result(
                        BackendStartActuationResult.Status.STALE_AUTHORITY
                );
                case "CONFLICT" -> result(
                        BackendStartActuationResult.Status.CONFLICT
                );
                case "REJECTED" -> result(
                        BackendStartActuationResult.Status.REJECTED
                );
                default -> result(
                        BackendStartActuationResult.Status.REJECTED
                );
            };
        }

        if (statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500) {
            return result(
                    BackendStartActuationResult.Status.ACTUATOR_UNAVAILABLE
            );
        }

        return result(BackendStartActuationResult.Status.REJECTED);
    }

    private static CompletionStage<BackendStartActuationResult> completed(
            BackendStartActuationResult.Status status
    ) {
        return CompletableFuture.completedFuture(result(status));
    }

    private static BackendStartActuationResult result(
            BackendStartActuationResult.Status status
    ) {
        return BackendStartActuationResult.of(status);
    }
}

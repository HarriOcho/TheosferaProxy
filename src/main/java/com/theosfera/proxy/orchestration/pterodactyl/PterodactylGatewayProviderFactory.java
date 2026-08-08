package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.proxy.orchestration.BackendOrchestrationProvider;
import com.theosfera.proxy.orchestration.FencedBackendOrchestrationProvider;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class PterodactylGatewayProviderFactory {

    private PterodactylGatewayProviderFactory() {
    }

    public static Optional<BackendOrchestrationProvider> create(
            PterodactylGatewayConfig config
    ) {
        return create(config, System::getenv);
    }

    static Optional<BackendOrchestrationProvider> create(
            PterodactylGatewayConfig config,
            Function<String, String> environmentReader
    ) {
        PterodactylGatewayConfig nonNullConfig = Objects.requireNonNull(
                config,
                "config cannot be null"
        );
        Function<String, String> nonNullEnvironmentReader =
                Objects.requireNonNull(
                        environmentReader,
                        "environmentReader cannot be null"
                );

        if (!nonNullConfig.enabled()) {
            return Optional.empty();
        }

        String token = nonNullEnvironmentReader.apply(
                nonNullConfig.tokenEnvironmentVariable()
        );
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Pterodactyl orchestration gateway token environment variable is missing: "
                            + nonNullConfig.tokenEnvironmentVariable()
            );
        }

        PterodactylGatewayTransport transport =
                new JdkPterodactylGatewayTransport(
                        nonNullConfig,
                        token
                );
        PterodactylGatewayBackendStartActuator actuator =
                new PterodactylGatewayBackendStartActuator(transport);

        return Optional.of(
                new FencedBackendOrchestrationProvider(
                        new PterodactylGatewayTargetResolver(nonNullConfig),
                        actuator
                )
        );
    }
}

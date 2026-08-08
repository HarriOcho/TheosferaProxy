package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.proxy.orchestration.BackendOrchestrationProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PterodactylGatewayProviderFactoryTest {

    @Test
    void disabledConfigCreatesNoProvider() {
        PterodactylGatewayConfig config = new PterodactylGatewayConfig(
                false,
                URI.create("https://127.0.0.1:25610"),
                Duration.ofSeconds(5),
                "THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN",
                Map.of()
        );

        Optional<BackendOrchestrationProvider> provider =
                PterodactylGatewayProviderFactory.create(
                        config,
                        key -> null
                );

        assertTrue(provider.isEmpty());
    }

    @Test
    void enabledConfigFailsClosedWhenGatewayTokenIsMissing() {
        PterodactylGatewayConfig config = enabledConfig();

        assertThrows(
                IllegalStateException.class,
                () -> PterodactylGatewayProviderFactory.create(
                        config,
                        key -> null
                )
        );
    }

    @Test
    void enabledConfigCreatesFencedProviderWhenTokenExists() {
        Optional<BackendOrchestrationProvider> provider =
                PterodactylGatewayProviderFactory.create(
                        enabledConfig(),
                        key -> "test-gateway-token"
                );

        assertTrue(provider.isPresent());
        assertInstanceOf(
                com.theosfera.proxy.orchestration
                        .FencedBackendOrchestrationProvider.class,
                provider.orElseThrow()
        );
    }

    private static PterodactylGatewayConfig enabledConfig() {
        return new PterodactylGatewayConfig(
                true,
                URI.create("https://orchestration.internal.example:25610"),
                Duration.ofSeconds(5),
                "THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN",
                Map.of("lobby-2", "ptero-server-42")
        );
    }
}

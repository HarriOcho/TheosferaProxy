package com.theosfera.proxy.orchestration.pterodactyl;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PterodactylGatewayConfigLoaderTest {

    @TempDir
    Path directory;

    @Test
    void createsDisabledDefaultConfiguration() {
        PterodactylGatewayConfigLoader loader =
                new PterodactylGatewayConfigLoader(
                        directory,
                        policy()
                );

        PterodactylGatewayConfig config = loader.load();

        assertFalse(config.enabled());
        assertTrue(config.targets().isEmpty());
        assertEquals(
                URI.create("https://127.0.0.1:25610/v1/backend-start"),
                config.startEndpoint()
        );
        assertTrue(Files.exists(loader.configFile()));
    }

    @Test
    void loadsTrustedGameplayTargets() throws Exception {
        Files.writeString(
                directory.resolve(PterodactylGatewayConfigLoader.FILE_NAME),
                """
                enabled=true
                gateway-uri=https://orchestration.internal.example:25610
                request-timeout-seconds=7
                gateway-token-env=THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN
                target.lobby-1=ptero-lobby-one
                target.skyblock-1=ptero-skyblock-one
                """
        );

        PterodactylGatewayConfig config =
                new PterodactylGatewayConfigLoader(
                        directory,
                        policy()
                ).load();

        assertTrue(config.enabled());
        assertEquals(Duration.ofSeconds(7), config.requestTimeout());
        assertEquals(
                Map.of(
                        "lobby-1", "ptero-lobby-one",
                        "skyblock-1", "ptero-skyblock-one"
                ),
                config.targets()
        );
    }

    @Test
    void rejectsAuthAsOrdinaryColdStartTarget() throws Exception {
        Files.writeString(
                directory.resolve(PterodactylGatewayConfigLoader.FILE_NAME),
                """
                enabled=true
                gateway-uri=https://orchestration.internal.example:25610
                request-timeout-seconds=5
                gateway-token-env=THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN
                target.auth-1=ptero-auth-one
                """
        );

        assertThrows(
                IllegalStateException.class,
                () -> new PterodactylGatewayConfigLoader(
                        directory,
                        policy()
                ).load()
        );
    }

    @Test
    void rejectsUnauthorizedBackendMapping() throws Exception {
        Files.writeString(
                directory.resolve(PterodactylGatewayConfigLoader.FILE_NAME),
                """
                enabled=true
                gateway-uri=https://orchestration.internal.example:25610
                request-timeout-seconds=5
                gateway-token-env=THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN
                target.unknown-1=ptero-unknown
                """
        );

        assertThrows(
                IllegalStateException.class,
                () -> new PterodactylGatewayConfigLoader(
                        directory,
                        policy()
                ).load()
        );
    }

    @Test
    void enabledConfigurationRequiresHttpsAndUniqueTargetReferences() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PterodactylGatewayConfig(
                        true,
                        URI.create("http://orchestration.internal.example:25610"),
                        Duration.ofSeconds(5),
                        "THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN",
                        Map.of("lobby-1", "target-one")
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PterodactylGatewayConfig(
                        true,
                        URI.create("https://orchestration.internal.example:25610"),
                        Duration.ofSeconds(5),
                        "THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN",
                        Map.of(
                                "lobby-1", "same-target",
                                "skyblock-1", "same-target"
                        )
                )
        );
    }

    private static BackendAuthorizationPolicy policy() {
        return new BackendAuthorizationPolicy(
                Map.of(
                        "auth-1",
                        new BackendPolicyEntry(BackendType.AUTH, 1, 100),
                        "lobby-1",
                        new BackendPolicyEntry(BackendType.LOBBY, 100, 100),
                        "skyblock-1",
                        new BackendPolicyEntry(
                                BackendType.SKYBLOCK,
                                200,
                                100
                        )
                )
        );
    }
}

package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendPolicyConfigLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndLoadsDefaultConfiguration() {
        BackendPolicyConfigLoader loader =
                new BackendPolicyConfigLoader(
                        temporaryDirectory
                );

        BackendAuthorizationPolicy policy =
                loader.load();

        assertTrue(Files.isRegularFile(
                loader.configFile()
        ));

        assertEquals(
                BackendType.AUTH,
                policy.allowedBackends().get("auth-1")
        );
        assertEquals(
                BackendType.LOBBY,
                policy.allowedBackends().get("lobby-1")
        );
        assertEquals(
                BackendType.SKYBLOCK,
                policy.allowedBackends().get("skyblock-1")
        );
    }

    @Test
    void loadsConfiguredBackendInstances() throws IOException {
        Path configFile = temporaryDirectory.resolve(
                BackendPolicyConfigLoader.FILE_NAME
        );

        Files.writeString(
                configFile,
                """
                auth-1=auth
                lobby-1=lobby
                lobby-2=LOBBY
                skyblock-1=skyblock
                """,
                StandardCharsets.UTF_8
        );

        BackendAuthorizationPolicy policy =
                new BackendPolicyConfigLoader(
                        temporaryDirectory
                ).load();

        assertEquals(4, policy.allowedBackends().size());
        assertEquals(
                BackendType.LOBBY,
                policy.allowedBackends().get("lobby-2")
        );
    }

    @Test
    void preservesExistingConfiguration()
            throws IOException {
        Path configFile = temporaryDirectory.resolve(
                BackendPolicyConfigLoader.FILE_NAME
        );

        String customConfiguration = """
                custom-lobby=LOBBY
                """;

        Files.writeString(
                configFile,
                customConfiguration,
                StandardCharsets.UTF_8
        );

        BackendAuthorizationPolicy policy =
                new BackendPolicyConfigLoader(
                        temporaryDirectory
                ).load();

        assertEquals(
                BackendType.LOBBY,
                policy.allowedBackends()
                        .get("custom-lobby")
        );
        assertEquals(
                customConfiguration,
                Files.readString(
                        configFile,
                        StandardCharsets.UTF_8
                )
        );
    }

    @Test
    void rejectsUnknownBackendType()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        BackendPolicyConfigLoader.FILE_NAME
                ),
                "lobby-1=UNKNOWN",
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> new BackendPolicyConfigLoader(
                        temporaryDirectory
                ).load()
        );
    }

    @Test
    void rejectsEmptyConfiguration()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        BackendPolicyConfigLoader.FILE_NAME
                ),
                "# No configured backends",
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> new BackendPolicyConfigLoader(
                        temporaryDirectory
                ).load()
        );
    }

    @Test
    void rejectsNullDataDirectory() {
        assertThrows(
                NullPointerException.class,
                () -> new BackendPolicyConfigLoader(null)
        );
    }
}
package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendPolicyConfigLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndLoadsDefaultConfiguration() throws IOException {
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
                new BackendPolicyEntry(
                        BackendType.AUTH,
                        1,
                        100
                ),
                policy.backendEntries().get("auth-1")
        );
        assertEquals(
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        100,
                        90
                ),
                policy.backendEntries().get("lobby-1")
        );
        assertEquals(
                new BackendPolicyEntry(
                        BackendType.SKYBLOCK,
                        200,
                        80
                ),
                policy.backendEntries().get("skyblock-1")
        );

        String generatedConfiguration = Files.readString(
                loader.configFile(),
                StandardCharsets.UTF_8
        );

        assertTrue(generatedConfiguration.contains(
                "auth-1=AUTH,1,100"
        ));
        assertTrue(generatedConfiguration.contains(
                "lobby-1=LOBBY,100,90"
        ));
        assertTrue(generatedConfiguration.contains(
                "skyblock-1=SKYBLOCK,200,80"
        ));
        assertFalse(generatedConfiguration.contains(
                "auth-1=AUTH\n"
        ));
    }

    @Test
    void loadsConfiguredBackendInstances() throws IOException {
        Path configFile = temporaryDirectory.resolve(
                BackendPolicyConfigLoader.FILE_NAME
        );

        Files.writeString(
                configFile,
                """
                auth-1=auth,1,100
                lobby-1=lobby,100,90
                lobby-2=LOBBY,150,95
                skyblock-1=skyblock,200,80
                """,
                StandardCharsets.UTF_8
        );

        BackendAuthorizationPolicy policy =
                new BackendPolicyConfigLoader(
                        temporaryDirectory
                ).load();

        assertEquals(4, policy.backendEntries().size());
        assertEquals(
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        150,
                        95
                ),
                policy.backendEntries().get("lobby-2")
        );
    }

    @Test
    void preservesExistingConfiguration()
            throws IOException {
        Path configFile = temporaryDirectory.resolve(
                BackendPolicyConfigLoader.FILE_NAME
        );

        String customConfiguration = """
        custom-lobby=LOBBY,250,75
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
                new BackendPolicyEntry(
                        BackendType.LOBBY,
                        250,
                        75
                ),
                policy.backendEntries().get("custom-lobby")
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
                "lobby-1=UNKNOWN,100,90",
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
    void rejectsIncompleteBackendEntry()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        BackendPolicyConfigLoader.FILE_NAME
                ),
                "lobby-1=LOBBY,100",
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
    void rejectsNonNumericCapacity()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        BackendPolicyConfigLoader.FILE_NAME
                ),
                "lobby-1=LOBBY,many,90",
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
    void rejectsNonNumericPreference()
            throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(
                        BackendPolicyConfigLoader.FILE_NAME
                ),
                "lobby-1=LOBBY,100,high",
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

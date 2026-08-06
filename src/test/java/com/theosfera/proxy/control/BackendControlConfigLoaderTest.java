package com.theosfera.proxy.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendControlConfigLoaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createsSafeDisabledDefaultConfiguration() throws Exception {
        BackendControlConfigLoader loader =
                new BackendControlConfigLoader(tempDirectory);

        BackendControlConfig config = loader.load();

        assertTrue(Files.isRegularFile(loader.configFile()));
        assertFalse(config.enabled());
        assertEquals("127.0.0.1", config.bindHost());
        assertEquals(25590, config.bindPort());
        assertEquals(Duration.ofSeconds(5), config.authenticationTimeout());
        assertEquals(
                tempDirectory.resolve("control-server.p12").normalize(),
                config.keyStorePath()
        );
        assertEquals(
                "THEOSFERA_CONTROL_KEYSTORE_PASSWORD",
                config.keyStorePasswordEnvironmentVariable()
        );
        assertEquals(
                tempDirectory.resolve("control-secrets.properties").normalize(),
                config.secretsFile()
        );
    }

    @Test
    void loadsEnabledConfigurationAndResolvesRelativePaths()
            throws Exception {
        Files.writeString(
                tempDirectory.resolve(BackendControlConfigLoader.FILE_NAME),
                """
                        enabled=true
                        bind-host=0.0.0.0
                        bind-port=25591
                        authentication-timeout-seconds=7
                        keystore-path=tls/proxy.p12
                        keystore-password-env=CONTROL_PASSWORD
                        secrets-file=secrets/control.properties
                        """,
                StandardCharsets.UTF_8
        );

        BackendControlConfig config =
                new BackendControlConfigLoader(tempDirectory).load();

        assertTrue(config.enabled());
        assertEquals("0.0.0.0", config.bindHost());
        assertEquals(25591, config.bindPort());
        assertEquals(Duration.ofSeconds(7), config.authenticationTimeout());
        assertEquals(
                tempDirectory.resolve("tls/proxy.p12").normalize(),
                config.keyStorePath()
        );
        assertEquals(
                tempDirectory.resolve("secrets/control.properties").normalize(),
                config.secretsFile()
        );
    }

    @Test
    void rejectsNonBooleanEnabledValue() throws Exception {
        writeConfig("sometimes", "25590", "5");

        assertThrows(
                IllegalStateException.class,
                () -> new BackendControlConfigLoader(tempDirectory).load()
        );
    }

    @Test
    void rejectsInvalidPort() throws Exception {
        writeConfig("true", "70000", "5");

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendControlConfigLoader(tempDirectory).load()
        );
    }

    @Test
    void rejectsNonPositiveAuthenticationTimeout() throws Exception {
        writeConfig("true", "25590", "0");

        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendControlConfigLoader(tempDirectory).load()
        );
    }

    private void writeConfig(
            String enabled,
            String port,
            String timeoutSeconds
    ) throws Exception {
        Files.writeString(
                tempDirectory.resolve(BackendControlConfigLoader.FILE_NAME),
                """
                        enabled=%s
                        bind-host=127.0.0.1
                        bind-port=%s
                        authentication-timeout-seconds=%s
                        keystore-path=control-server.p12
                        keystore-password-env=CONTROL_PASSWORD
                        secrets-file=control-secrets.properties
                        """.formatted(enabled, port, timeoutSeconds),
                StandardCharsets.UTF_8
        );
    }
}

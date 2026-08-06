package com.theosfera.proxy.control;

import com.theosfera.protocol.message.payload.BackendType;
import com.theosfera.proxy.backend.BackendAuthorizationPolicy;
import com.theosfera.proxy.backend.BackendHealthRegistry;
import com.theosfera.proxy.backend.BackendPolicyEntry;
import com.theosfera.proxy.backend.PendingBackendPingRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BackendControlRuntimeTest {

    @TempDir
    Path tempDirectory;

    @Test
    void disabledDefaultDoesNotReadEnvironmentOrTlsMaterial() {
        AtomicBoolean environmentRead = new AtomicBoolean();

        BackendControlRuntime runtime = BackendControlRuntime.create(
                tempDirectory,
                policy(),
                pendingPingRegistry(),
                healthRegistry(),
                mock(Logger.class),
                name -> {
                    environmentRead.set(true);
                    throw new AssertionError(
                            "disabled runtime must not read environment"
                    );
                }
        );

        assertFalse(runtime.enabled());
        assertFalse(runtime.started());
        assertFalse(environmentRead.get());
        assertThrows(
                IllegalStateException.class,
                runtime::requireMessageSender
        );

        runtime.start();

        assertTrue(runtime.started());
        assertFalse(environmentRead.get());

        runtime.stop();

        assertFalse(runtime.started());
        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    void enabledRuntimeFailsClosedWhenKeystorePasswordEnvIsMissing()
            throws Exception {
        Files.writeString(
                tempDirectory.resolve(BackendControlConfigLoader.FILE_NAME),
                """
                        enabled=true
                        bind-host=127.0.0.1
                        bind-port=25590
                        authentication-timeout-seconds=5
                        keystore-path=control-server.p12
                        keystore-password-env=CONTROL_PASSWORD
                        secrets-file=control-secrets.properties
                        """,
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> BackendControlRuntime.create(
                        tempDirectory,
                        policy(),
                        pendingPingRegistry(),
                        healthRegistry(),
                        mock(Logger.class),
                        name -> null
                )
        );
    }

    private static PendingBackendPingRegistry pendingPingRegistry() {
        return new PendingBackendPingRegistry(
                Clock.systemUTC(),
                Duration.ofSeconds(10)
        );
    }

    private static BackendHealthRegistry healthRegistry() {
        return new BackendHealthRegistry(
                Clock.systemUTC(),
                Duration.ofSeconds(15)
        );
    }

    private static BackendAuthorizationPolicy policy() {
        return new BackendAuthorizationPolicy(
                Map.of(
                        "lobby-1",
                        new BackendPolicyEntry(
                                BackendType.LOBBY,
                                100,
                                90
                        )
                )
        );
    }
}

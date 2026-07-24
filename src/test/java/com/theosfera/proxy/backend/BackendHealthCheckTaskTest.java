package com.theosfera.proxy.backend;

import com.theosfera.protocol.message.payload.BackendType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendHealthCheckTaskTest {

    @Test
    void processesAllAuthorizedBackends() {
        BackendPingEmitter emitter =
                mock(BackendPingEmitter.class);

        BackendHealthCheckTask task =
                new BackendHealthCheckTask(
                        policy(),
                        emitter,
                        mock(Logger.class)
                );

        task.run();

        verify(emitter).emit("auth-1");
        verify(emitter).emit("lobby-1");
        verify(emitter).emit("skyblock-1");
    }

    @Test
    void continuesAfterBackendFailure() {
        RuntimeException exception =
                new RuntimeException("boom");
        BackendPingEmitter emitter =
                mock(BackendPingEmitter.class);
        Logger logger = mock(Logger.class);

        when(emitter.emit("auth-1"))
                .thenThrow(exception);

        BackendHealthCheckTask task =
                new BackendHealthCheckTask(
                        policy(),
                        emitter,
                        logger
                );

        task.run();

        verify(emitter).emit("auth-1");
        verify(emitter).emit("lobby-1");
        verify(emitter).emit("skyblock-1");
        verify(logger).warn(
                "Health check de backend fallo para {}.",
                "auth-1",
                exception
        );
    }

    @Test
    void doesNotDependOnTransferTargetResolver() {
        for (java.lang.reflect.Field field
                : BackendHealthCheckTask.class.getDeclaredFields()) {
            assertFalse(
                    field.getType()
                            .getName()
                            .contains("TransferTargetResolver")
            );
        }
    }

    private BackendAuthorizationPolicy policy() {
        return new BackendAuthorizationPolicy(
                Map.of(
                        "auth-1",
                        BackendType.AUTH,
                        "lobby-1",
                        BackendType.LOBBY,
                        "skyblock-1",
                        BackendType.SKYBLOCK
                )
        );
    }
}

package com.theosfera.proxy.coordination.velocity;

import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.CoordinationStateRegistry;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityCoordinationAdmissionListenerTest {

    @Test
    void deniesLoginUnlessCoordinationIsHealthy() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        CoordinationStateRegistry states = new CoordinationStateRegistry();
        VelocityCoordinationAdmissionListener listener =
                new VelocityCoordinationAdmissionListener(
                        proxyServer,
                        states,
                        mock(Logger.class)
                );
        LoginEvent event = mock(LoginEvent.class);

        listener.onLogin(event);

        verify(event).setResult(
                org.mockito.ArgumentMatchers.argThat(
                        result -> !result.isAllowed()
                )
        );
    }

    @Test
    void allowsHealthyLoginWithoutOverridingResult() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        CoordinationStateRegistry states = new CoordinationStateRegistry();
        states.set(CoordinationState.HEALTHY);
        VelocityCoordinationAdmissionListener listener =
                new VelocityCoordinationAdmissionListener(
                        proxyServer,
                        states,
                        mock(Logger.class)
                );
        LoginEvent event = mock(LoginEvent.class);

        listener.onLogin(event);

        verify(event, org.mockito.Mockito.never()).setResult(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void fencingDisconnectsExistingPlayers() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Player first = mock(Player.class);
        Player second = mock(Player.class);
        when(proxyServer.getPlayerCount()).thenReturn(2);
        when(proxyServer.getAllPlayers()).thenReturn(List.of(first, second));

        VelocityCoordinationAdmissionListener listener =
                new VelocityCoordinationAdmissionListener(
                        proxyServer,
                        new CoordinationStateRegistry(),
                        mock(Logger.class)
                );

        listener.onStateChanged(
                CoordinationState.DEGRADED,
                CoordinationState.FENCED
        );

        verify(first).disconnect(org.mockito.ArgumentMatchers.any());
        verify(second).disconnect(org.mockito.ArgumentMatchers.any());
    }
}

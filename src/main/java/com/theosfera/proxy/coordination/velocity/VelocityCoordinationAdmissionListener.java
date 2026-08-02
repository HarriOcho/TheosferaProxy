package com.theosfera.proxy.coordination.velocity;

import com.theosfera.proxy.coordination.CoordinationState;
import com.theosfera.proxy.coordination.CoordinationStateListener;
import com.theosfera.proxy.coordination.CoordinationStateRegistry;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Objects;

public final class VelocityCoordinationAdmissionListener
        implements CoordinationStateListener {

    private static final Component UNAVAILABLE_MESSAGE = Component.text(
            "La coordinacion global de Theosfera no esta disponible. Intenta nuevamente en unos momentos."
    );
    private static final Component FENCED_MESSAGE = Component.text(
            "Este Proxy perdio su autoridad distribuida. Reconecta en unos momentos."
    );

    private final ProxyServer proxyServer;
    private final CoordinationStateRegistry stateRegistry;
    private final Logger logger;

    public VelocityCoordinationAdmissionListener(
            ProxyServer proxyServer,
            CoordinationStateRegistry stateRegistry,
            Logger logger
    ) {
        this.proxyServer = Objects.requireNonNull(
                proxyServer,
                "proxyServer cannot be null"
        );
        this.stateRegistry = Objects.requireNonNull(
                stateRegistry,
                "stateRegistry cannot be null"
        );
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        if (stateRegistry.get() != CoordinationState.HEALTHY) {
            event.setResult(
                    ResultedEvent.ComponentResult.denied(UNAVAILABLE_MESSAGE)
            );
        }
    }

    @Override
    public void onStateChanged(
            CoordinationState previous,
            CoordinationState current
    ) {
        logger.info(
                "Estado de coordinacion distribuida: {} -> {}.",
                previous,
                current
        );

        if (current != CoordinationState.FENCED) {
            return;
        }

        logger.error(
                "El Proxy fue fenced; se desconectaran {} jugadores para evitar autoridad distribuida obsoleta.",
                proxyServer.getPlayerCount()
        );
        proxyServer.getAllPlayers().forEach(
                player -> player.disconnect(FENCED_MESSAGE)
        );
    }
}

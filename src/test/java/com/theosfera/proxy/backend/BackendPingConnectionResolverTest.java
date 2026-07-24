package com.theosfera.proxy.backend;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendPingConnectionResolverTest {

    private final ProxyServer proxyServer =
            mock(ProxyServer.class);
    private final BackendPingConnectionResolver resolver =
            new BackendPingConnectionResolver(proxyServer);

    @Test
    void findsValidConnection() {
        ServerConnection connection =
                connection("lobby-1");
        RegisteredServer server =
                server(
                        "lobby-1",
                        List.of(player(connection))
                );

        when(proxyServer.getServer("lobby-1"))
                .thenReturn(Optional.of(server));

        assertEquals(
                connection,
                resolver.resolve("lobby-1").orElseThrow()
        );
    }

    @Test
    void returnsEmptyWhenServerIsNotRegistered() {
        when(proxyServer.getServer("lobby-1"))
                .thenReturn(Optional.empty());

        assertTrue(
                resolver.resolve("lobby-1").isEmpty()
        );
    }

    @Test
    void returnsEmptyWhenServerHasNoPlayers() {
        RegisteredServer server =
                server("lobby-1", List.of());

        when(proxyServer.getServer("lobby-1"))
                .thenReturn(Optional.of(server));

        assertTrue(
                resolver.resolve("lobby-1").isEmpty()
        );
    }

    @Test
    void ignoresPlayersWithoutCurrentServer() {
        Player player = mock(Player.class);

        when(player.getCurrentServer())
                .thenReturn(Optional.empty());

        RegisteredServer server =
                server("lobby-1", List.of(player));

        when(proxyServer.getServer("lobby-1"))
                .thenReturn(Optional.of(server));

        assertTrue(
                resolver.resolve("lobby-1").isEmpty()
        );
    }

    @Test
    void ignoresConnectionWithDifferentServerName() {
        ServerConnection connection =
                connection("auth-1");
        RegisteredServer server =
                server(
                        "lobby-1",
                        List.of(player(connection))
                );

        when(proxyServer.getServer("lobby-1"))
                .thenReturn(Optional.of(server));

        assertTrue(
                resolver.resolve("lobby-1").isEmpty()
        );
    }

    private RegisteredServer server(
            String serverName,
            List<Player> players
    ) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);
        when(server.getServerInfo()).thenReturn(serverInfo);
        when(server.getPlayersConnected()).thenReturn(players);

        return server;
    }

    private Player player(ServerConnection connection) {
        Player player = mock(Player.class);

        when(player.getCurrentServer())
                .thenReturn(Optional.of(connection));

        return player;
    }

    private ServerConnection connection(String serverName) {
        ServerConnection connection =
                mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(serverInfo.getName()).thenReturn(serverName);
        when(connection.getServerInfo()).thenReturn(serverInfo);

        return connection;
    }
}

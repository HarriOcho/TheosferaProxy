package com.theosfera.proxy.messaging.handler;

import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.message.ProtocolMessageType;
import com.theosfera.protocol.message.payload.PlayerServerReadyPayload;
import com.theosfera.proxy.messaging.ProtocolMessageContext;
import com.theosfera.proxy.session.PlayerPresenceRuntimeService;
import com.theosfera.proxy.session.PlayerPresenceUpdateResult;
import com.theosfera.proxy.session.PlayerServerPresence;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerServerReadyDistributedRuntimeTest {

    private static final UUID PLAYER_ID =
            UUID.fromString(
                    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            );

    @Test
    void forwardsCarrierPlayerAndReadyPresenceToDistributedRuntime() {
        PlayerPresenceRuntimeService runtimeService =
                mock(PlayerPresenceRuntimeService.class);
        Logger logger = mock(Logger.class);
        Player player = mock(Player.class);
        ServerConnection source = mock(ServerConnection.class);
        ServerInfo serverInfo = mock(ServerInfo.class);

        when(source.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn("lobby-1");
        when(source.getPlayer()).thenReturn(player);
        when(runtimeService.publishReady(
                org.mockito.ArgumentMatchers.same(player),
                org.mockito.ArgumentMatchers.any(PlayerServerPresence.class)
        )).thenReturn(PlayerPresenceUpdateResult.RECORDED);

        PlayerServerReadyMessageHandler handler =
                new PlayerServerReadyMessageHandler(
                        runtimeService,
                        logger
                );

        ProtocolEnvelope<PlayerServerReadyPayload> envelope =
                ProtocolEnvelope.create(
                        ProtocolMessageType.PLAYER_SERVER_READY,
                        new PlayerServerReadyPayload(
                                PLAYER_ID,
                                "lobby-1",
                                2_000L
                        )
                );

        handler.handle(
                new ProtocolMessageContext(
                        source,
                        envelope
                )
        );

        ArgumentCaptor<PlayerServerPresence> captor =
                ArgumentCaptor.forClass(
                        PlayerServerPresence.class
                );
        verify(runtimeService).publishReady(
                org.mockito.ArgumentMatchers.same(player),
                captor.capture()
        );

        PlayerServerPresence presence = captor.getValue();
        assertEquals(PLAYER_ID, presence.playerId());
        assertEquals("lobby-1", presence.backendName());
        assertEquals(2_000L, presence.readyAt());

        verify(logger).info(
                "Jugador {} listo en {}.",
                PLAYER_ID,
                "lobby-1"
        );
        assertSame(player, source.getPlayer());
    }
}

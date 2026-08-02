package com.theosfera.proxy.messaging;

import com.theosfera.proxy.backend.BackendMessageAuthorizer;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolMessageListenerCoordinationGateTest {

    @Test
    void rejectsProtocolDispatchWhenCoordinationIsNotHealthy() {
        BackendMessageAuthorizer authorizer =
                mock(BackendMessageAuthorizer.class);
        ProtocolMessageDispatcher dispatcher =
                mock(ProtocolMessageDispatcher.class);
        PluginMessageEvent event = mock(PluginMessageEvent.class);

        when(event.getIdentifier()).thenReturn(
                ProtocolChannel.IDENTIFIER
        );

        ProtocolMessageListener listener = new ProtocolMessageListener(
                mock(Logger.class),
                authorizer,
                dispatcher,
                () -> false
        );

        listener.onPluginMessage(event);

        verify(event).setResult(
                PluginMessageEvent.ForwardResult.handled()
        );
        verify(authorizer, never()).isAuthorized(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        verify(dispatcher, never()).dispatch(
                org.mockito.ArgumentMatchers.any()
        );
    }
}

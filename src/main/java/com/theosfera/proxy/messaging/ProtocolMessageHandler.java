package com.theosfera.proxy.messaging;

public interface ProtocolMessageHandler {

    String messageType();

    void handle(ProtocolMessageContext context);
}
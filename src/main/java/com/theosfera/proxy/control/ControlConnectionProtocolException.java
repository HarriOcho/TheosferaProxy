package com.theosfera.proxy.control;

import java.io.IOException;

public final class ControlConnectionProtocolException
        extends IOException {

    public ControlConnectionProtocolException(String message) {
        super(message);
    }

    public ControlConnectionProtocolException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}

package com.theosfera.proxy.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@FunctionalInterface
public interface AuthenticatedControlConnectionHandler {

    void handle(
            BackendControlSession session,
            InputStream input,
            OutputStream output
    ) throws IOException;
}

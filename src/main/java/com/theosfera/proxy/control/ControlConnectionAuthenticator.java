package com.theosfera.proxy.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface ControlConnectionAuthenticator {

    Optional<BackendControlSessionRegistration> authenticate(
            UUID connectionId,
            InputStream input,
            OutputStream output
    ) throws IOException;
}

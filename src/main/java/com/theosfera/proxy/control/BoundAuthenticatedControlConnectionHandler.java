package com.theosfera.proxy.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

public final class BoundAuthenticatedControlConnectionHandler
        implements AuthenticatedControlConnectionHandler {

    private final BackendControlMessageSender messageSender;
    private final AuthenticatedControlConnectionHandler delegate;

    public BoundAuthenticatedControlConnectionHandler(
            BackendControlMessageSender messageSender,
            AuthenticatedControlConnectionHandler delegate
    ) {
        this.messageSender = Objects.requireNonNull(
                messageSender,
                "messageSender cannot be null"
        );
        this.delegate = Objects.requireNonNull(
                delegate,
                "delegate cannot be null"
        );
    }

    @Override
    public void handle(
            BackendControlSession session,
            InputStream input,
            OutputStream output
    ) throws IOException {
        BackendControlSession nonNullSession = Objects.requireNonNull(
                session,
                "session cannot be null"
        );
        InputStream nonNullInput = Objects.requireNonNull(
                input,
                "input cannot be null"
        );
        OutputStream nonNullOutput = Objects.requireNonNull(
                output,
                "output cannot be null"
        );

        if (!messageSender.bind(nonNullSession, nonNullOutput)) {
            throw new ControlConnectionProtocolException(
                    "Authenticated control session is no longer current"
            );
        }

        try {
            delegate.handle(
                    nonNullSession,
                    nonNullInput,
                    nonNullOutput
            );
        } finally {
            messageSender.unbindIfCurrent(nonNullSession);
        }
    }
}

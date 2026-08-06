package com.theosfera.proxy.control;

import com.theosfera.protocol.transport.ProtocolFrameCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;

public final class RejectUnexpectedControlMessageHandler
        implements AuthenticatedControlConnectionHandler {

    private final ProtocolFrameCodec frameCodec;

    public RejectUnexpectedControlMessageHandler(
            ProtocolFrameCodec frameCodec
    ) {
        this.frameCodec = Objects.requireNonNull(
                frameCodec,
                "frameCodec cannot be null"
        );
    }

    @Override
    public void handle(
            BackendControlSession session,
            InputStream input,
            OutputStream output
    ) throws IOException {
        Objects.requireNonNull(
                session,
                "session cannot be null"
        );
        InputStream nonNullInput = Objects.requireNonNull(
                input,
                "input cannot be null"
        );
        Objects.requireNonNull(
                output,
                "output cannot be null"
        );

        Optional<byte[]> frame = frameCodec.readFrame(nonNullInput);

        if (frame.isPresent()) {
            throw new ControlConnectionProtocolException(
                    "Post-authentication control messages are not enabled yet"
            );
        }
    }
}

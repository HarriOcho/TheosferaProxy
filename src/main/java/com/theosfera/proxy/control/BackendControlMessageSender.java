package com.theosfera.proxy.control;

import com.theosfera.protocol.codec.ProtocolJsonCodec;
import com.theosfera.protocol.message.ProtocolEnvelope;
import com.theosfera.protocol.transport.ProtocolFrameCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendControlMessageSender {

    private final ProtocolJsonCodec jsonCodec;
    private final ProtocolFrameCodec frameCodec;
    private final BackendControlSessionRegistry sessionRegistry;
    private final Map<String, BoundOutput> outputsByBackend =
            new ConcurrentHashMap<>();

    public BackendControlMessageSender(
            ProtocolJsonCodec jsonCodec,
            ProtocolFrameCodec frameCodec,
            BackendControlSessionRegistry sessionRegistry
    ) {
        this.jsonCodec = Objects.requireNonNull(
                jsonCodec,
                "jsonCodec cannot be null"
        );
        this.frameCodec = Objects.requireNonNull(
                frameCodec,
                "frameCodec cannot be null"
        );
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry cannot be null"
        );
    }

    public Optional<BackendControlSession> findSession(
            String backendName
    ) {
        return sessionRegistry.find(backendName);
    }

    public boolean bind(
            BackendControlSession session,
            OutputStream output
    ) {
        BackendControlSession nonNullSession = Objects.requireNonNull(
                session,
                "session cannot be null"
        );
        OutputStream nonNullOutput = Objects.requireNonNull(
                output,
                "output cannot be null"
        );

        if (!sessionRegistry.isCurrent(nonNullSession)) {
            return false;
        }

        String backendName = nonNullSession.identity().serverName();
        BoundOutput next = new BoundOutput(
                nonNullSession,
                nonNullOutput
        );

        outputsByBackend.compute(
                backendName,
                (ignored, existing) -> {
                    if (!sessionRegistry.isCurrent(nonNullSession)) {
                        return existing;
                    }
                    return next;
                }
        );

        return outputsByBackend.get(backendName) == next;
    }

    public boolean unbindIfCurrent(
            BackendControlSession session
    ) {
        BackendControlSession nonNullSession = Objects.requireNonNull(
                session,
                "session cannot be null"
        );
        String backendName = nonNullSession.identity().serverName();
        BoundOutput existing = outputsByBackend.get(backendName);

        if (existing == null
                || !existing.session.equals(nonNullSession)) {
            return false;
        }

        return outputsByBackend.remove(backendName, existing);
    }

    public boolean send(
            BackendControlSession expectedSession,
            ProtocolEnvelope<?> envelope
    ) throws IOException {
        BackendControlSession nonNullSession = Objects.requireNonNull(
                expectedSession,
                "expectedSession cannot be null"
        );
        ProtocolEnvelope<?> nonNullEnvelope = Objects.requireNonNull(
                envelope,
                "envelope cannot be null"
        );
        String backendName = nonNullSession.identity().serverName();
        BoundOutput boundOutput = outputsByBackend.get(backendName);

        if (boundOutput == null
                || !boundOutput.session.equals(nonNullSession)) {
            return false;
        }

        synchronized (boundOutput) {
            if (outputsByBackend.get(backendName) != boundOutput
                    || !sessionRegistry.isCurrent(nonNullSession)) {
                outputsByBackend.remove(backendName, boundOutput);
                return false;
            }

            try {
                frameCodec.writeFrame(
                        boundOutput.output,
                        jsonCodec.encode(nonNullEnvelope)
                );
                return true;
            } catch (IOException exception) {
                outputsByBackend.remove(backendName, boundOutput);
                throw exception;
            }
        }
    }

    public int boundSessionCount() {
        return outputsByBackend.size();
    }

    public void clear() {
        outputsByBackend.clear();
    }

    private static final class BoundOutput {

        private final BackendControlSession session;
        private final OutputStream output;

        private BoundOutput(
                BackendControlSession session,
                OutputStream output
        ) {
            this.session = session;
            this.output = output;
        }
    }
}

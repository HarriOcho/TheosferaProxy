package com.theosfera.proxy.transfer;

import com.theosfera.protocol.message.payload.TransferResultStatus;

import java.util.Objects;

public record PlayerTransferCompletion(
        TransferResultStatus status,
        String message
) {

    public PlayerTransferCompletion {
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );

        message = Objects.requireNonNull(
                message,
                "message cannot be null"
        ).trim();

        if (message.isEmpty()) {
            throw new IllegalArgumentException(
                    "message cannot be blank"
            );
        }
    }

    public static PlayerTransferCompletion success() {
        return new PlayerTransferCompletion(
                TransferResultStatus.SUCCESS,
                "Player transferred successfully"
        );
    }

    public static PlayerTransferCompletion rejected() {
        return new PlayerTransferCompletion(
                TransferResultStatus.REJECTED,
                "Target backend rejected the transfer"
        );
    }

    public static PlayerTransferCompletion failed() {
        return new PlayerTransferCompletion(
                TransferResultStatus.FAILED,
                "Player transfer failed"
        );
    }

    public static PlayerTransferCompletion timedOut() {
        return new PlayerTransferCompletion(
                TransferResultStatus.TIMED_OUT,
                "Player transfer timed out"
        );
    }
}

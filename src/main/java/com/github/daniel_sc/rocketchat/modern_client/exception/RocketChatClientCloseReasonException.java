package com.github.daniel_sc.rocketchat.modern_client.exception;

import javax.websocket.CloseReason;

public class RocketChatClientCloseReasonException extends RocketChatClientRuntimeException {

    private final CloseReason closeReason;

    public RocketChatClientCloseReasonException(CloseReason closeReason) {
        super("connection closed: " + closeReason);
        this.closeReason = closeReason;
    }

    public CloseReason getCloseReason() {
        return closeReason;
    }
}

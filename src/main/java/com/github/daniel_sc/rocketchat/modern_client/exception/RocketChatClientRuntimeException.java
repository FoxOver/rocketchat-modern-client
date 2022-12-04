package com.github.daniel_sc.rocketchat.modern_client.exception;

public class RocketChatClientRuntimeException extends RuntimeException {

    public RocketChatClientRuntimeException() {
    }

    public RocketChatClientRuntimeException(String message) {
        super(message);
    }

    public RocketChatClientRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public RocketChatClientRuntimeException(Throwable cause) {
        super(cause);
    }

    public RocketChatClientRuntimeException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}

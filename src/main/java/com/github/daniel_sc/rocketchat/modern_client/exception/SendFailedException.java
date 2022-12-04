package com.github.daniel_sc.rocketchat.modern_client.exception;

import javax.websocket.SendResult;

public class SendFailedException extends RocketChatClientRuntimeException {

    public final SendResult result;

    public SendFailedException(SendResult result) {
        this.result = result;
    }
}

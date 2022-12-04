package com.github.daniel_sc.rocketchat.modern_client.response;

public class ErrorMessage {
    public boolean isClientSafe;
    public String error;
    public String reason;
    public String message;

    @Override
    public String toString() {
        return "Error{" +
                "isClientSafe='" + isClientSafe + '\'' +
                ", error=" + error +
                ", reason=" + reason +
                ", message=" + message +
                '}';
    }
}

package com.github.daniel_sc.rocketchat.modern_client.response;

public class FullChatMessage {
    public String msg;
    public User u;
    public String rid;
    public Room.RoomType roomType;
    public boolean unread;

    @Override
    public String toString() {
        return "ChatMessage{" +
                "msg='" + msg + '\'' +
                ", u=" + u + '\'' +
                ", rid=" + rid +
                ", roomType=" + roomType +
                ", unread=" + unread +
                '}';
    }
}

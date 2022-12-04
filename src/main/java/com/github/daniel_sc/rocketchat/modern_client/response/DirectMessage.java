package com.github.daniel_sc.rocketchat.modern_client.response;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

public class DirectMessage {
    @SerializedName("rid")
    public String id;
    @SerializedName("t")
    public Room.RoomType type;
    public JsonElement usernames;

    @Override
    public String toString() {
        return "Room{" +
                "rid='" + id + '\'' +
                ", type=" + type +
                ", creator=" + usernames +
                '}';
    }
}

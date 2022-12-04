package com.github.daniel_sc.rocketchat.modern_client.response;

import com.google.gson.annotations.SerializedName;

public class Subscription {
    public String name;
    /** roomId */
    public String rid;
    @SerializedName("t")
    public Room.RoomType type;
    public String _id;

    @Override
    public String toString() {
        return "Subscription{" +
                "name='" + name + '\'' +
                ", rid='" + rid + '\'' +
                ", _id='" + _id + '\'' +
                ", roomType='" + type + '\'' +
                '}';
    }
}

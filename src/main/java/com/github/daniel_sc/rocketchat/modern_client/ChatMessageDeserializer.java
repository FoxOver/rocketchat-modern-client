package com.github.daniel_sc.rocketchat.modern_client;

import com.github.daniel_sc.rocketchat.modern_client.response.FullChatMessage;
import com.google.gson.*;

import java.lang.reflect.Type;

public class ChatMessageDeserializer implements JsonDeserializer<FullChatMessage> {

    @Override
    public FullChatMessage deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {

        JsonObject result;
        result = json.getAsJsonObject().get("args").getAsJsonArray().get(0).getAsJsonArray().get(0).getAsJsonObject();
        result.add("roomType",json.getAsJsonObject().get("args").getAsJsonArray().get(1).getAsJsonObject().get("roomType"));
        Gson gson = new Gson();
        return gson.fromJson(result, FullChatMessage.class);
    }
}
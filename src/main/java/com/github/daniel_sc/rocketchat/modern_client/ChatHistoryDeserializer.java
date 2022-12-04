package com.github.daniel_sc.rocketchat.modern_client;

import com.github.daniel_sc.rocketchat.modern_client.response.ChatHistory;
import com.github.daniel_sc.rocketchat.modern_client.response.ChatMessage;
import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.List;

public class ChatHistoryDeserializer implements JsonDeserializer<ChatHistory> {

    @Override
    public ChatHistory deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {

        Gson gson = new Gson();
        ChatHistory chatHistory = new ChatHistory();
        List<ChatMessage> chatMessages = chatHistory.messages;

        JsonObject jsonObject = json.getAsJsonObject();
        JsonArray jsonMessages = jsonObject.get("messages").getAsJsonArray();
        for (JsonElement jsonMessage : jsonMessages) {
            ChatMessage chatMessage = gson.fromJson(jsonMessage.getAsJsonObject(), ChatMessage.class);
            chatMessages.add(chatMessage);
        }
        chatHistory.unreadNotLoaded = jsonObject.get("unreadNotLoaded").getAsInt();
        return chatHistory;
    }
}

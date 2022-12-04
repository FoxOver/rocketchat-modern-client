package com.github.daniel_sc.rocketchat.modern_client.response;

import java.util.ArrayList;
import java.util.List;

public class ChatHistory {

    public List<ChatMessage> messages = new ArrayList<>();
    public int unreadNotLoaded;

}

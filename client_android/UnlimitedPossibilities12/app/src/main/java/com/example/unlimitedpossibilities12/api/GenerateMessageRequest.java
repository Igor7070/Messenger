package com.example.unlimitedpossibilities12.api;

import java.util.List;

public class GenerateMessageRequest {
    List<Message> history;
    String preference;

    public GenerateMessageRequest(List<Message> history, String preference) {
        this.history = history;
        this.preference = preference;
    }
}

package com.example.unlimitedpossibilities12.api;

public class MessageRequest {
    private String prompt;
    private String targetLanguage;

    public MessageRequest(String prompt, String targetLanguage) {
        this.prompt = prompt;
        this.targetLanguage = targetLanguage;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }
}

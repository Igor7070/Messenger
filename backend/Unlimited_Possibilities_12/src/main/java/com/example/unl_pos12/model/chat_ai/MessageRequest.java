package com.example.unl_pos12.model.chat_ai;

public class MessageRequest {
    private String prompt;

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(String targetLanguage) {
        this.targetLanguage = targetLanguage;
    }

    private String targetLanguage;

    // Геттеры и сеттеры
    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }
}

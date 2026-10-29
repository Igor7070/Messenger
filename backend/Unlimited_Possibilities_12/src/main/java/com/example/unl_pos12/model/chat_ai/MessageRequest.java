package com.example.unl_pos12.model.chat_ai;

public class MessageRequest {
    private String prompt;
    private String targetLanguage;
    private String roomId; // ADDED

    // Геттеры и сеттеры
    public String getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(String targetLanguage) {
        this.targetLanguage = targetLanguage;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }
}

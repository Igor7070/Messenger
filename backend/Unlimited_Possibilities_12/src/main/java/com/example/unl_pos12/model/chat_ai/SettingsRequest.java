package com.example.unl_pos12.model.chat_ai;

public class SettingsRequest {
    private String roomId;
    private String userId;
    private boolean translationEnabled;
    private String translationLanguage;
    private boolean ttsEnabled;

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isTranslationEnabled() {
        return translationEnabled;
    }

    public void setTranslationEnabled(boolean translationEnabled) {
        this.translationEnabled = translationEnabled;
    }

    public String getTranslationLanguage() {
        return translationLanguage;
    }

    public void setTranslationLanguage(String translationLanguage) {
        this.translationLanguage = translationLanguage;
    }

    public boolean isTtsEnabled() {
        return ttsEnabled;
    }

    public void setTtsEnabled(boolean ttsEnabled) {
        this.ttsEnabled = ttsEnabled;
    }
}

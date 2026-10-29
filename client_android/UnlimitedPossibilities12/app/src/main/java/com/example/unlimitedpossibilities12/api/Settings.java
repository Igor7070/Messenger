package com.example.unlimitedpossibilities12.api;

public class Settings {
    private String roomId;
    private String userId;
    private boolean translationEnabled;
    private String translationLanguage;
    private boolean ttsEnabled;
    /** Перевод озвучивает сам телефон — серверу синтезировать не нужно. */
    private boolean localTts;

    public Settings(String roomId, String userId, boolean translationEnabled, String translationLanguage, boolean ttsEnabled) {
        this(roomId, userId, translationEnabled, translationLanguage, ttsEnabled, false);
    }

    public Settings(String roomId, String userId, boolean translationEnabled, String translationLanguage,
                    boolean ttsEnabled, boolean localTts) {
        this.localTts = localTts;
        this.roomId = roomId;
        this.userId = userId;
        this.translationEnabled = translationEnabled;
        this.translationLanguage = translationLanguage;
        this.ttsEnabled = ttsEnabled;
    }

    // Геттеры и сеттеры
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public boolean isTranslationEnabled() { return translationEnabled; }
    public void setTranslationEnabled(boolean translationEnabled) { this.translationEnabled = translationEnabled; }
    public String getTranslationLanguage() { return translationLanguage; }
    public void setTranslationLanguage(String translationLanguage) { this.translationLanguage = translationLanguage; }
    public boolean isTtsEnabled() { return ttsEnabled; }
    public void setTtsEnabled(boolean ttsEnabled) { this.ttsEnabled = ttsEnabled; }
}

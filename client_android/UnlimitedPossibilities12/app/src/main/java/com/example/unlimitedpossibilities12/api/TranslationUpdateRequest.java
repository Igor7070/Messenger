package com.example.unlimitedpossibilities12.api;

public class TranslationUpdateRequest {
    private String translatedContent;
    private String targetLanguage;

    public TranslationUpdateRequest(String translatedContent, String targetLanguage) {
        this.translatedContent = translatedContent;
        this.targetLanguage = targetLanguage;
    }

    public String getTranslatedContent() {
        return translatedContent;
    }

    public void setTranslatedContent(String translatedContent) {
        this.translatedContent = translatedContent;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(String targetLanguage) {
        this.targetLanguage = targetLanguage;
    }
}

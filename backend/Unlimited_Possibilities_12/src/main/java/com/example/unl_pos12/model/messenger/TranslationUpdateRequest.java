package com.example.unl_pos12.model.messenger;

public class TranslationUpdateRequest {
    private String translatedContent;
    private String targetLanguage; // Добавлено для указания языка перевода

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

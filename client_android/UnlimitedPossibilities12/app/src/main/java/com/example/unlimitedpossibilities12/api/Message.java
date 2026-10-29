package com.example.unlimitedpossibilities12.api;

import static android.content.ContentValues.TAG;

import android.util.Log;

import com.google.gson.annotations.SerializedName;

public class Message {
    private long id;
    private String content;
    private User sender;
    private Chat chat;
    private String fileUrl;
    @SerializedName("delivered_status")
    private boolean delivered;
    @SerializedName("read_status")
    private boolean read;
    @SerializedName("timestamp")
    private String timestamp; // Храним как строку в формате "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    private String translationLanguage;
    private String translatedContent;
    private boolean showOriginal = true;
    @SerializedName("messageType")
    private String messageType;  // *** ДОБАВЛЕНО: Поле для типа сообщения ***
    private String transcribedContent; // ДОБАВЛЕНО: Поле для транскрипции
    private String encryptedContent; // Для E2EE в секретных чатах
    private String nonce; // Для E2EE в секретных чатах
    private boolean isTemporary; // Новое поле для временных сообщений
    private Long publicKeyId; // [ADD]
    private boolean isFileDownloaded = false;
    private boolean isBeingProcessed = false;
    @SerializedName("contentType")
    private String contentType;

    // Конструкторы
    public Message() {
        this.isTemporary = false; // По умолчанию сообщение не временное
    }

    public Message(long id, String content, User sender, Chat chat, String fileUrl, boolean delivered, boolean read, String timestamp) {
        this.id = id;
        this.content = content;
        this.sender = sender;
        this.chat = chat;
        this.fileUrl = fileUrl;
        this.delivered = delivered;
        this.read = read;
        this.timestamp = timestamp;
        this.isTemporary = false; // По умолчанию не временное
    }

    // Геттеры и сеттеры
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public User getSender() {
        return sender;
    }

    public void setSender(User sender) {
        this.sender = sender;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public boolean isDelivered() {
        return delivered;
    }

    public void setDelivered(boolean delivered) {
        this.delivered = delivered;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getTranslationLanguage() {
        return translationLanguage;
    }

    public void setTranslationLanguage(String translationLanguage) {
        this.translationLanguage = translationLanguage;
    }

    public String getTranslatedContent() {
        return translatedContent;
    }

    public void setTranslatedContent(String translatedContent) {
        this.translatedContent = translatedContent;
    }

    public boolean isShowOriginal() {
        return showOriginal;
    }

    public void setShowOriginal(boolean showOriginal) {
        this.showOriginal = showOriginal;
    }
    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getTranscribedContent() {
        // Возвращаем transcribedContent только для аудиосообщений
        if ("audio".equalsIgnoreCase(messageType)) {
            return transcribedContent;
        }
        return null;
    }

    public void setTranscribedContent(String transcribedContent) {
        // Устанавливаем transcribedContent только для аудиосообщений
        if ("audio".equalsIgnoreCase(messageType)) {
            this.transcribedContent = transcribedContent;
        } else {
            this.transcribedContent = null;
            Log.w(TAG, "Attempt to set transcribedContent for non-audio message id=" + id);
        }
    }

    public String getEncryptedContent() {
        return encryptedContent;
    }

    public void setEncryptedContent(String encryptedContent) {
        this.encryptedContent = encryptedContent;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public boolean isTemporary() {
        return isTemporary;
    }

    public void setTemporary(boolean temporary) {
        this.isTemporary = temporary;
    }

    public Long getPublicKeyId() {
        return publicKeyId;
    }

    public void setPublicKeyId(Long publicKeyId) {
        this.publicKeyId = publicKeyId;
    }

    public boolean isFileDownloaded() {
        return isFileDownloaded;
    }

    public void setFileDownloaded(boolean fileDownloaded) {
        isFileDownloaded = fileDownloaded;
    }

    public boolean isBeingProcessed() {
        return isBeingProcessed;
    }

    public void setBeingProcessed(boolean beingProcessed) {
        isBeingProcessed = beingProcessed;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    // ДОБАВЛЕНО: Кэш превью ссылки, чтобы не мигало при обновлении сообщения
    private transient LinkPreviewData previewData;

    public static class LinkPreviewData {
        public String title;
        public String description;
        public String imageUrl;
        public String url;
    }

    public LinkPreviewData getPreviewData() {
        return previewData;
    }

    public void setPreviewData(LinkPreviewData previewData) {
        this.previewData = previewData;
    }
}

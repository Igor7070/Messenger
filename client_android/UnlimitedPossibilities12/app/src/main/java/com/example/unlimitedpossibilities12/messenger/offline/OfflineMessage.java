package com.example.unlimitedpossibilities12.messenger.offline;

import android.net.Uri;
import java.util.UUID;

public class OfflineMessage {

    private String messageId;
    private String text;
    private boolean isMine;
    private long timestamp;

    private Uri fileUri;
    private boolean isImage;
    private boolean isFile;
    private boolean isAudio;
    private String fileName;
    private String mimeType;
    private String caption;

    private boolean isSystemMessage = false;

    /** Статус своего сообщения: 0 — в очереди, 1 — отправлено, 2 — доставлено. */
    public static final int STATUS_QUEUED = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_DELIVERED = 2;
    private int status = STATUS_SENT; // старые сообщения из истории считаем отправленными

    // ==================== КОНСТРУКТОРЫ ====================

    /** Пустой конструктор — обязателен для Gson / Room */
    public OfflineMessage() {
    }

    /**
     * Конструктор для обычного текстового сообщения (используется при отправке)
     */
    public OfflineMessage(String text, boolean isMine) {
        this.messageId = UUID.randomUUID().toString();
        this.text = text;
        this.isMine = isMine;
        this.timestamp = System.currentTimeMillis();
        this.isImage = false;
        this.isFile = false;
        this.isAudio = false;
        this.isSystemMessage = false;
    }

    /**
     * Конструктор для текстового сообщения с уже известным messageId
     * (используется при получении сообщения от собеседника)
     */
    public OfflineMessage(String text, boolean isMine, String messageId) {
        this.messageId = (messageId != null && !messageId.isEmpty())
                ? messageId
                : UUID.randomUUID().toString();

        this.text = text;
        this.isMine = isMine;
        this.timestamp = System.currentTimeMillis();
        this.isImage = false;
        this.isFile = false;
        this.isAudio = false;
        this.isSystemMessage = false;
    }

    /**
     * Конструктор для служебных / системных сообщений
     */
    public OfflineMessage(String systemData, boolean isMine, boolean isSystem) {
        this.messageId = UUID.randomUUID().toString();
        this.text = systemData;
        this.isMine = isMine;
        this.timestamp = System.currentTimeMillis();
        this.isImage = false;
        this.isFile = false;
        this.isAudio = false;
        this.isSystemMessage = isSystem;
    }

    /**
     * Главный конструктор для файлов (изображения, аудио, обычные файлы)
     */
    public OfflineMessage(Uri fileUri, boolean isMine, boolean forceAsImage) {
        this.messageId = UUID.randomUUID().toString();
        this.fileUri = fileUri;
        this.isMine = isMine;
        this.timestamp = System.currentTimeMillis();
        this.isImage = forceAsImage;
        this.isFile = true;
        this.isAudio = false;
        this.fileName = getFileNameFromUri(fileUri);
        this.text = this.fileName != null ? this.fileName : "[File]";
        this.isSystemMessage = false;

        // Автоопределение типа аудио
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                    lower.endsWith(".aac") || lower.endsWith(".wav") ||
                    lower.endsWith(".ogg")) {
                this.isAudio = true;
                this.isImage = false;
                this.isFile = false;
            }
        }
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null) return "[File]";
        String last = uri.getLastPathSegment();
        return last != null ? last : "[File]";
    }

    // ==================== ГЕТТЕРЫ ====================

    public String getMessageId() {
        if (messageId == null || messageId.isEmpty()) {
            messageId = UUID.randomUUID().toString();
        }
        return messageId;
    }

    public String getText() {
        return text != null ? text : "";
    }

    public boolean isMine() {
        return isMine;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Uri getFileUri() {
        return fileUri;
    }

    public boolean isImage() {
        return isImage;
    }

    public boolean isFile() {
        return isFile;
    }

    public boolean isAudio() {
        return isAudio;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getCaption() {
        return caption;
    }

    public int getStatus() { return status; }

    public void setStatus(int status) { this.status = status; }

    public boolean isSystemMessage() {
        return isSystemMessage;
    }

    // ==================== СЕТТЕРЫ ====================

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public void setText(String newText) {
        this.text = newText;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public void setIsAudio(boolean isAudio) {
        this.isAudio = isAudio;
        if (isAudio) {
            this.isImage = false;
            this.isFile = false;
        }
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    // Для Gson / сериализации
    public void setIsMine(boolean isMine) {
        this.isMine = isMine;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setFileUri(Uri fileUri) {
        this.fileUri = fileUri;
    }

    public void setIsImage(boolean isImage) {
        this.isImage = isImage;
    }

    public void setIsFile(boolean isFile) {
        this.isFile = isFile;
    }

    public void setIsSystemMessage(boolean isSystemMessage) {
        this.isSystemMessage = isSystemMessage;
    }
}

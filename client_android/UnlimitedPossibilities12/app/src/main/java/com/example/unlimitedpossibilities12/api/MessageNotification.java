package com.example.unlimitedpossibilities12.api;

public class MessageNotification {
    private int userId;
    private int recipientId;
    private String content;
    private long chatId;

    public MessageNotification(int userId, int recipientId, String content, long chatId) {
        this.userId = userId;
        this.recipientId = recipientId;
        this.content = content;
        this.chatId = chatId;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public int getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(int recipientId) {
        this.recipientId = recipientId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getChatId() {
        return chatId;
    }

    public void setChatId(long chatId) {
        this.chatId = chatId;
    }
}

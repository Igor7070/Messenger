package com.example.unlimitedpossibilities12.api;

public class CallRequestBody {
    private int callerId;
    private int recipientId;

    public CallRequestBody(int callerId, int recipientId) {
        this.callerId = callerId;
        this.recipientId = recipientId;
    }

    // Геттеры и сеттеры
    public int getCallerId() {
        return callerId;
    }

    public void setCallerId(int callerId) {
        this.callerId = callerId;
    }

    public int getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(int recipientId) {
        this.recipientId = recipientId;
    }
}

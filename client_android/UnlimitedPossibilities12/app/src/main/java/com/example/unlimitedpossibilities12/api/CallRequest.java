package com.example.unlimitedpossibilities12.api;

import java.io.Serializable;

public class CallRequest implements Serializable {
    private String type; // Тип звонка: "voice" или "video"
    private int callerId;
    private String roomId;
    private String callerName;
    private int recipientId;
    private String callerAvatar;

    public CallRequest() {}

    public CallRequest(int callerId, int recipientId, String roomId) {
        this.callerId = callerId;
        this.recipientId = recipientId;
        this.roomId = roomId;
    }

    public CallRequest(int callerId, int recipientId, String roomId, String callerName) {
        this.callerId = callerId;
        this.recipientId = recipientId;
        this.roomId = roomId;
        this.callerName = callerName;
    }

    public CallRequest(int callerId, int recipientId, String roomId, String callerName, String type) {
        this.callerId = callerId;
        this.recipientId = recipientId;
        this.roomId = roomId;
        this.callerName = callerName;
        this.type = type;
    }

    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public int getCallerId() { return callerId; }
    public void setCallerId(int callerId) { this.callerId = callerId; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getCallerName() { return callerName; }
    public void setCallerName(String callerName) { this.callerName = callerName; }
    public int getRecipientId() {
        return recipientId;
    }
    public void setRecipientId(int recipientId) {
        this.recipientId = recipientId;
    }
    public String getCallerAvatar() { return callerAvatar; }
    public void setCallerAvatar(String callerAvatar) { this.callerAvatar = callerAvatar; }

    @Override
    public String toString() {
        return "CallRequest{" +
                "type='" + type + '\'' +
                ", callerId=" + callerId +
                ", roomId='" + roomId + '\'' +
                ", callerName='" + callerName + '\'' +
                ", recipientId=" + recipientId +
                ", callerAvatar='" + callerAvatar + '\'' +
                '}';
    }
}

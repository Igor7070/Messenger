package com.example.unlimitedpossibilities12.api;

import java.io.Serializable;
import java.util.List;

public class GroupCallRequest implements Serializable {
    private String type;
    private String initiatorId;
    private List<String> recipientIds;
    private String roomId;
    private String initiatorName;

    public GroupCallRequest() {
    }

    public GroupCallRequest(String initiatorId, String initiatorName, List<String> recipientIds, String roomId, String type) {
        this.initiatorId = initiatorId;
        this.initiatorName = initiatorName;
        this.recipientIds = recipientIds;
        this.roomId = roomId;
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getInitiatorId() {
        return initiatorId;
    }

    public void setInitiatorId(String initiatorId) {
        this.initiatorId = initiatorId;
    }

    public List<String> getRecipientIds() {
        return recipientIds;
    }

    public void setRecipientIds(List<String> recipientIds) {
        this.recipientIds = recipientIds;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getInitiatorName() {
        return initiatorName;
    }

    public void setInitiatorName(String initiatorName) {
        this.initiatorName = initiatorName;
    }

    @Override
    public String toString() {
        return "GroupCallRequest{" +
                "type='" + type + '\'' +
                ", initiatorId='" + initiatorId + '\'' +
                ", recipientIds=" + recipientIds +
                ", roomId='" + roomId + '\'' +
                ", initiatorName='" + initiatorName + '\'' +
                '}';
    }
}

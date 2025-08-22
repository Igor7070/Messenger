package com.example.unl_pos12.model.messenger;

import java.util.List;

public class GroupCallRequest {
    private String type; // Тип вызова (например, "group")
    private String initiatorId; // ID инициатора
    private List<String> recipientIds; // Список ID получателей
    private String roomId; // ID комнаты
    private String initiatorName; // Имя инициатора (для уведомлений)

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

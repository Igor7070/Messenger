package com.example.unlimitedpossibilities12.api;

import java.io.Serializable;
import java.util.List;

public class Call implements Serializable {
    private long id;
    private String caller; // Для индивидуальных звонков
    private String receiver; // Для индивидуальных звонков
    private String initiatorId; // Для групповых звонков
    private List<String> participants; // Для групповых звонков
    private String direction; // "incoming" или "outgoing"
    private String status; // "missed", "completed", "rejected"
    private String callType; // "voice", "video", "voice_with_translate", "group"
    private String timestamp;
    private String roomId;

    // Конструктор для индивидуальных звонков
    public Call(long id, String caller, String receiver, String direction, String status, String callType, String timestamp) {
        this.id = id;
        this.caller = caller;
        this.receiver = receiver;
        this.direction = direction;
        this.status = status;
        this.callType = callType;
        this.timestamp = timestamp;
    }

    // Конструктор для групповых звонков
    public Call(long id, String initiatorId, List<String> participants, String direction, String status, String callType, String timestamp, String roomId) {
        this.id = id;
        this.initiatorId = initiatorId;
        this.participants = participants;
        this.direction = direction;
        this.status = status;
        this.callType = callType;
        this.timestamp = timestamp;
        this.roomId = roomId;
    }

    // Геттеры
    public long getId() { return id; }
    public String getCaller() { return caller; }
    public String getReceiver() { return receiver; }
    public String getInitiatorId() { return initiatorId; }
    public List<String> getParticipants() { return participants; }
    public String getDirection() { return direction; }
    public String getStatus() { return status; }
    public String getCallType() { return callType; }
    public String getTimestamp() { return timestamp; }
    public String getRoomId() { return roomId; }
}

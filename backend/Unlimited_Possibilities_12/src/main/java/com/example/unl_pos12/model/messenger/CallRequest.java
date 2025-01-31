package com.example.unl_pos12.model.messenger;

public class CallRequest {
    private String callerId; // ID вызывающего
    private String recipientId; // ID получателя
    private String roomId; // ID комнаты для звонка
    //private SignalData signal; // Объект с сигналом WebRTC (например, offer, answer)

    // Геттеры и сеттеры
    public String getCallerId() {
        return callerId;
    }

    public void setCallerId(String callerId) {
        this.callerId = callerId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    /*public SignalData getSignal() {
        return signal;
    }

    public void setSignal(SignalData signal) {
        this.signal = signal;
    }*/

    @Override
    public String toString() {
        return "CallRequest{" +
                "callerId='" + callerId + '\'' +
                ", recipientId='" + recipientId + '\'' +
                ", roomId='" + roomId + '\'' +
                '}';
    }
}

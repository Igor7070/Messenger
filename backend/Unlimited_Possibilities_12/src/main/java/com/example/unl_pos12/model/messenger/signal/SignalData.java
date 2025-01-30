package com.example.unl_pos12.model.messenger.signal;

public class SignalData {
    private String type; // Тип сигнала (offer, answer, candidate)
    private String sdp; // SDP-строка

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSdp() {
        return sdp;
    }

    public void setSdp(String sdp) {
        this.sdp = sdp;
    }
}

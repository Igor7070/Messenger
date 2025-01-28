package com.example.unl_pos12.model.messenger.signal;

public class SignalMessage {
    private String from; // Идентификатор отправителя
    private SignalData signal; // Объект с сигналом WebRTC

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public SignalData getSignal() {
        return signal;
    }

    public void setSignal(SignalData signal) {
        this.signal = signal;
    }
}

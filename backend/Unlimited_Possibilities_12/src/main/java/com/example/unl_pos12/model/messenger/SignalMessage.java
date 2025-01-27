package com.example.unl_pos12.model.messenger;

public class SignalMessage {
    private String from; // Идентификатор отправителя
    private String signal; // Сигнал WebRTC (ICE-кандидаты, SDP и т.д.)

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getSignal() {
        return signal;
    }

    public void setSignal(String signal) {
        this.signal = signal;
    }
}

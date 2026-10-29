package com.example.unl_pos12.model.messenger.signal;

public class SignalMessage {
    private String from; // Идентификатор отправителя
    private String to;   // Идентификатор получателя (опционально для групповых звонков)
    private SignalData signal; // Объект с сигналом WebRTC

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public SignalData getSignal() {
        return signal;
    }

    public void setSignal(SignalData signal) {
        this.signal = signal;
    }

    @Override
    public String toString() {
        return "SignalMessage{" +
                "from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", signal=" + signal +
                '}';
    }
}

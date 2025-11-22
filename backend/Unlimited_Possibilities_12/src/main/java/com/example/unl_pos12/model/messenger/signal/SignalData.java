package com.example.unl_pos12.model.messenger.signal;

public class SignalData {
    private String type; // Тип сигнала (offer, answer, candidate)
    private String sdp; // SDP-строка
    private IceCandidate iceCandidate;
    private String signature;     // подпись SDP (base64)
    private String publicKey;     // публичный ключ подписи Ed25519 (base64)

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

    public IceCandidate getIceCandidate() {
        return iceCandidate;
    }

    public void setIceCandidate(IceCandidate iceCandidate) {
        this.iceCandidate = iceCandidate;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    @Override
    public String toString() {
        return "SignalData{" +
                "type='" + type + '\'' +
                ", sdp='" + sdp + '\'' +
                ", iceCandidate=" + iceCandidate +
                ", signature=" + signature +
                ", publicKey=" + publicKey +
                '}';
    }
}

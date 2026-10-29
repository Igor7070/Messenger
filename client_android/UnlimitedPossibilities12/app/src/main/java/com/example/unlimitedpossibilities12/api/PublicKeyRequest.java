package com.example.unlimitedpossibilities12.api;

public class PublicKeyRequest {
    private Long userId;
    private String publicKey;

    public PublicKeyRequest(Long userId, String publicKey) {
        this.userId = userId;
        this.publicKey = publicKey;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPublicKey() {
        return publicKey;
    }
}

package com.example.unlimitedpossibilities12.api;

public class FileMetadata {
    private String fileUrl;
    private String nonce;
    private Long publicKeyId;

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public Long getPublicKeyId() {
        return publicKeyId;
    }

    public void setPublicKeyId(Long publicKeyId) {
        this.publicKeyId = publicKeyId;
    }
}

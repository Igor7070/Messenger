package com.example.unlimitedpossibilities12.api;

public class FileUploadResponse {
    private String fileUrl;
    private String error;

    public FileUploadResponse() {}

    public FileUploadResponse(String fileUrl, String error) {
        this.fileUrl = fileUrl;
        this.error = error;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}

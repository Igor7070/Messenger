package com.example.unl_pos12.model.messenger;

public class FileUploadResponse {
    private String fileUrl;
    private String fileUrlSelf; // Новое поле
    private String fileName; // Новое поле
    private String error;

    // Конструктор для обратной совместимости
    public FileUploadResponse(String fileUrl, String error) {
        this.fileUrl = fileUrl;
        this.fileUrlSelf = null;
        this.fileName = null;
        this.error = error;
    }

    // Новый конструктор
    public FileUploadResponse(String fileUrl, String fileUrlSelf, String fileName, String error) {
        this.fileUrl = fileUrl;
        this.fileUrlSelf = fileUrlSelf;
        this.fileName = fileName;
        this.error = error;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getFileUrlSelf() {
        return fileUrlSelf;
    }

    public void setFileUrlSelf(String fileUrlSelf) {
        this.fileUrlSelf = fileUrlSelf;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
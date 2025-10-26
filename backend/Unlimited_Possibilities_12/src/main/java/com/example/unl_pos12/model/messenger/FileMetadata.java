package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

@Entity
@Table(name = "file_metadata")
public class FileMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_url")
    private String fileUrl;

    @Column(name = "file_url_self") // Новое поле
    private String fileUrlSelf;

    @Column(name = "nonce")
    private String nonce;

    @Column(name = "public_key_id")
    private Long publicKeyId;

    @ManyToOne
    @JoinColumn(name = "message_id")
    private Message message;

    @Column(name = "file_name") // Новое поле
    private String fileName;

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getFileUrlSelf() { return fileUrlSelf; }
    public void setFileUrlSelf(String fileUrlSelf) { this.fileUrlSelf = fileUrlSelf; }
    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }
    public Long getPublicKeyId() { return publicKeyId; }
    public void setPublicKeyId(Long publicKeyId) { this.publicKeyId = publicKeyId; }
    public Message getMessage() { return message; }
    public void setMessage(Message message) { this.message = message; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
}

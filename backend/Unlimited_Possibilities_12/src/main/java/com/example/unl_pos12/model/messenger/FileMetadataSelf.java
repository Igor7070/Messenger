package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

@Entity
@Table(name = "file_metadata_self")
public class FileMetadataSelf {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_url_self")
    private String fileUrlSelf;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "nonce")
    private String nonce;

    @Column(name = "public_key_id")
    private Long publicKeyId;

    @ManyToOne
    @JoinColumn(name = "message_id")
    private Message message;

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileUrlSelf() { return fileUrlSelf; }
    public void setFileUrlSelf(String fileUrlSelf) { this.fileUrlSelf = fileUrlSelf; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }
    public Long getPublicKeyId() { return publicKeyId; }
    public void setPublicKeyId(Long publicKeyId) { this.publicKeyId = publicKeyId; }
    public Message getMessage() { return message; }
    public void setMessage(Message message) { this.message = message; }
}

package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "file_metadata")
public class FileMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_url")
    private String fileUrl;

    @Column(name = "nonce")
    private String nonce;

    @Column(name = "public_key_id")
    private Long publicKeyId;

    @ManyToOne
    @JoinColumn(name = "message_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Message message;

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }
    public Long getPublicKeyId() { return publicKeyId; }
    public void setPublicKeyId(Long publicKeyId) { this.publicKeyId = publicKeyId; }
    public Message getMessage() { return message; }
    public void setMessage(Message message) { this.message = message; }
}

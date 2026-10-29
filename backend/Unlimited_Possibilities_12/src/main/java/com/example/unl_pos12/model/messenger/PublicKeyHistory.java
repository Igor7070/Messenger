package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

import java.time.ZonedDateTime;

@Entity
@Table(name = "public_keys_history")
public class PublicKeyHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String publicKey;
    @Column(nullable = false)
    private ZonedDateTime createdAt;
    @Column
    private ZonedDateTime validUntil;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
    public ZonedDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(ZonedDateTime validUntil) { this.validUntil = validUntil; }
}

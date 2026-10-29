package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

import java.time.ZonedDateTime;

/**
 * Ключ подписи (Ed25519) пользователя для защищённых звонков.
 *
 * Хранится отдельно от ключей шифрования секретного чата (X25519 в
 * public_keys / public_keys_history). Раньше оба типа ключей писались в один
 * слот /api/public_keys и перезаписывали друг друга: после защищённого звонка
 * собеседник начинал шифровать сообщения чата на ключ подписи, и они
 * становились нечитаемыми.
 */
@Entity
@Table(name = "sign_keys")
public class SignKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String publicKey;

    @Column(nullable = false)
    private ZonedDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
}

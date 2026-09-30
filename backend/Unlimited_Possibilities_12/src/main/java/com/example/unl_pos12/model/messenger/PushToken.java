package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

import java.time.ZonedDateTime;

/**
 * Токен устройства для push-уведомлений (Firebase Cloud Messaging).
 *
 * У одного пользователя может быть несколько устройств, поэтому ключ —
 * сам токен; при переустановке приложения Firebase выдаёт новый, а старый
 * перестаёт работать и удаляется при первой неудачной отправке.
 */
@Entity
@Table(name = "push_tokens")
public class PushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @Column(nullable = false)
    private Long userId;

    /** android | web — пригодится, когда добавим push в браузере. */
    private String platform;

    private ZonedDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public ZonedDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(ZonedDateTime updatedAt) { this.updatedAt = updatedAt; }
}

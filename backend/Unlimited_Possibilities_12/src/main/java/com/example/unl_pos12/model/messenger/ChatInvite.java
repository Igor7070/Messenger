package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Ссылка-приглашение в закрытую группу.
 *
 * Закрытая группа не видна в общем списке, поэтому попасть в неё можно двумя
 * путями: админ добавляет человека сам или выдаёт ссылку. У ссылки есть срок и
 * предел числа входов — иначе, разойдясь по пересылкам, она открывала бы группу
 * кому угодно и навсегда.
 */
@Entity
@Table(name = "chat_invites")
public class ChatInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Случайная строка из ссылки; по ней и ищем приглашение. */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** null — бессрочная. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** 0 — без предела. */
    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(nullable = false)
    private int uses;

    /** Отозванная ссылка перестаёт работать, но остаётся видна админу. */
    @Column(nullable = false)
    private boolean revoked;

    public ChatInvite() {
    }

    public ChatInvite(String token, Long chatId, Long createdBy, Instant expiresAt, int maxUses) {
        this.token = token;
        this.chatId = chatId;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
    }

    /** Годна ли ссылка прямо сейчас. */
    public boolean isUsable() {
        if (revoked) return false;
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) return false;
        return maxUses <= 0 || uses < maxUses;
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(int maxUses) {
        this.maxUses = maxUses;
    }

    public int getUses() {
        return uses;
    }

    public void setUses(int uses) {
        this.uses = uses;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }
}

package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Участник группового чата и его роль.
 *
 * До появления этой таблицы у групп не было участников вовсе: проверка прав
 * отвечала «да» любому вошедшему, поэтому чужую группу мог прочитать и удалить
 * кто угодно, а push по групповым сообщениям было некому слать — получателей
 * искали по списку приватных чатов.
 *
 * Для чатов на двоих участие по-прежнему выражается связью user.privateChats:
 * там роли не нужны, обе стороны равны.
 */
@Entity
@Table(name = "chat_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chat_id", "user_id"}))
public class ChatMember {

    /** Права вложены: владелец умеет всё, что админ, а админ — всё, что участник. */
    public enum Role {
        /** Создатель. Один на чат. Удаляет чат, назначает админов, передаёт владение. */
        OWNER,
        /** Приглашает и исключает, переименовывает, выпускает и отзывает ссылки. */
        ADMIN,
        /** Читает, пишет, выходит сам. */
        MEMBER;

        public boolean canManageMembers() {
            return this == OWNER || this == ADMIN;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.MEMBER;

    /** Нужен, чтобы при уходе владельца без админов передать права самому давнему. */
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    public ChatMember() {
    }

    public ChatMember(Long chatId, Long userId, Role role) {
        this.chatId = chatId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}

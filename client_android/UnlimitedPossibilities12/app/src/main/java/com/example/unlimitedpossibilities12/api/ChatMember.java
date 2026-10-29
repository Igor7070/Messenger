package com.example.unlimitedpossibilities12.api;

/**
 * Участник группового чата: кто и с какой ролью.
 *
 * Сервер отдаёт заодно имя и аватар, чтобы экран участников не делал ещё по
 * запросу на каждого человека.
 */
public class ChatMember {
    private long userId;
    private String role;      // OWNER, ADMIN, MEMBER
    private String username;
    private String avatar;
    private String joinedAt;

    public long getUserId() {
        return userId;
    }

    public String getRole() {
        return role == null ? "MEMBER" : role;
    }

    public String getUsername() {
        return username;
    }

    public String getAvatar() {
        return avatar;
    }

    public String getJoinedAt() {
        return joinedAt;
    }

    public boolean isOwner() {
        return "OWNER".equals(getRole());
    }

    public boolean isAdmin() {
        return "ADMIN".equals(getRole());
    }

    /** Права вложены: владелец умеет всё, что админ. */
    public boolean canManageMembers() {
        return isOwner() || isAdmin();
    }

    /** Подпись роли для списка. */
    public String roleLabel() {
        if (isOwner()) return "owner";
        if (isAdmin()) return "admin";
        return "member";
    }
}

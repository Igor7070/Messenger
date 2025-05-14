package com.example.unl_pos12.model.messenger;

public class UserStatusMessage {
    private Long userId;
    private String username;
    private boolean online;

    public UserStatusMessage(Long userId, String username, boolean online) {
        this.userId = userId;
        this.username = username;
        this.online = online;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
}

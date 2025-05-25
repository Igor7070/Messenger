package com.example.unl_pos12.model.messenger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password; // Храните пароли в зашифрованном виде

    //@Lob // Указываем, что это большое поле
    private String info; // Информация о пользователе
    private String avatar; // Путь к аватару или байтовый массив
    private boolean online; // "online" или "offline"
    private Long lastHeartbeat; // Временная метка последнего сердцебиения (в миллисекундах)

    @ManyToMany
    @JoinTable(
            name = "user_chats",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "chat_id")
    )
    @JsonIgnore // Исключаем privateChats из сериализации
    private List<Chat> privateChats = new ArrayList<>(); // Список приватных чатов

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public List<Chat> getPrivateChats() {
        return privateChats;
    }

    public void setPrivateChats(List<Chat> privateChats) {
        this.privateChats = privateChats;
    }

    public boolean isOnline() { return online; }

    public void setOnline(boolean online) { this.online = online; }
    public Long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(Long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }
}

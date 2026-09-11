package com.example.unl_pos12.model.messenger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    // WRITE_ONLY: пароль принимается из JSON (регистрация/логин), но никогда
    // не отдаётся наружу. Без этого он уходил в открытом виде в объекте sender
    // каждого сообщения всем участникам чата и в GET /api/users.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
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

    /** Токен доступа к API. Не хранится в базе, заполняется только в ответе логина/регистрации. */
    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String token;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

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

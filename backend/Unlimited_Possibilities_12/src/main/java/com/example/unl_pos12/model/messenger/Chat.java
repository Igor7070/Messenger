package com.example.unl_pos12.model.messenger;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
public class Chat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private boolean isPrivate;

    //@OneToMany(mappedBy = "chat", cascade = CascadeType.ALL)
    @OneToMany(mappedBy = "chat", fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<Message> messages;

    @ManyToMany(mappedBy = "privateChats") // Обратная сторона связи
    @JsonBackReference // Указываем, что это обратная ссылка
    private List<User> users = new ArrayList<>(); // Список пользователей в чате

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setIsPrivate(boolean isPrivate) {
        this.isPrivate = isPrivate;
    }
}

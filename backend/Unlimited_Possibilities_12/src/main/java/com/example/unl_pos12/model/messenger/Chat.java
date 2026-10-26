package com.example.unl_pos12.model.messenger;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

import java.util.List;

@Entity
public class Chat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @JsonProperty("private")
    private boolean isPrivate;
    private Boolean isSecret = false; // Поле для секретных чатов
    /**
     * Кто создал чат. Нужен, чтобы групповой чат мог удалить только его автор:
     * участников у групп нет, и без этого поля удалять мог любой вошедший.
     * У чатов, созданных раньше, остаётся null.
     */
    private Long createdBy;
    /**
     * Видна ли группа всем. Пусто у чатов, созданных до появления поля, — такие
     * считаем публичными, иначе единственная существующая группа закрылась бы
     * от тех, кто в ней переписывается. Новые создаются закрытыми.
     */
    private Boolean isPublic;
    /** Картинка группы: путь вида /avatars/group_12.jpg. У чатов на двоих не используется. */
    private String avatar;

    //@OneToMany(mappedBy = "chat", cascade = CascadeType.ALL)
    @OneToMany(mappedBy = "chat", fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<Message> messages;

    /**
     * Участники чата на двоих — чтобы список разговоров знал собеседника.
     *
     * В базе не хранится: заполняется при отдаче списка приватных чатов.
     * Без этого клиенту приходилось вычислять собеседника разбором имени
     * («А_Б»), а имя человека само может содержать «_» — тогда в списке
     * показывался обрезок вместо имени.
     */
    @Transient
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<User> participants;

    public List<User> getParticipants() {
        return participants;
    }

    public void setParticipants(List<User> participants) {
        this.participants = participants;
    }

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

    public Boolean getIsSecret() {
        return isSecret;
    }

    public void setIsSecret(Boolean isSecret) {
        this.isSecret = isSecret;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Boolean getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}

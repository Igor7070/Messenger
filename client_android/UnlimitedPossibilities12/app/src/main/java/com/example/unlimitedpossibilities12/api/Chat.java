package com.example.unlimitedpossibilities12.api;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

public class Chat {
    private long id;
    private String name;
    @SerializedName("private")
    private boolean isPrivate;
    private Boolean isSecret; // Поле для секретных чатов
    /** Кто создал чат: групповой удаляет только его создатель. Для старых чатов null. */
    private Long createdBy;
    /** Видна ли группа всем. Пусто у чатов, созданных до появления поля — такие публичные. */
    private Boolean isPublic;
    /** Картинка группы вида /avatars/group_12.jpg; у чатов на двоих не используется. */
    private String avatar;
    private String lastMessage;
    private String timestamp;
    private int unreadCount;
    @SerializedName("messages")
    private List<Message> messages;

    public Chat() {
        this.messages = new ArrayList<>();
    }

    public Chat(long chatId, String name) {
        this.id = chatId;
        this.name = name;
        this.messages = new ArrayList<>();
    }

    public Chat(String name) {
        this.name = name;
        this.messages = new ArrayList<>();
    }

    public Chat(String name, boolean isPrivate) {
        this.name = name;
        this.isPrivate = isPrivate;
        this.messages = new ArrayList<>();
    }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    /** Для старых чатов признака нет — считаем их публичными, как и сервер. */
    public boolean isPublicGroup() { return !Boolean.FALSE.equals(isPublic); }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isPrivate() { return isPrivate; }
    public void setPrivate(boolean isPrivate) { this.isPrivate = isPrivate; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }

    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }

    public Boolean getIsSecret() {
        return isSecret;
    }
    public void setIsSecret(Boolean isSecret) {
        this.isSecret = isSecret;
    }
}

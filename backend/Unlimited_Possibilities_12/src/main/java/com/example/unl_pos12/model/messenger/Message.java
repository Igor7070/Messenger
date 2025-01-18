package com.example.unl_pos12.model.messenger;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;

@Entity
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String content;
    private String fileUrl; // URL файла...
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sender_id")
    private User sender;
    //private LocalDateTime timestamp;
    //@ManyToOne(fetch = FetchType.EAGER)
    //@JoinColumn(name = "chat_id")
    @ManyToOne
    @JoinColumn(name = "chat_id")
    @JsonBackReference
    private Chat chat;
    private Long delivered; // Статус доставки
    private Long read;      // Статус прочтения

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public User getSender() {
        return sender;
    }

    public void setSender(User sender) {
        this.sender = sender;
    }

    /*public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }*/

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public Long getDelivered() {
        return delivered;
    }

    public void setDelivered(Long delivered) {
        this.delivered = delivered;
    }

    public Long getRead() {
        return read;
    }

    public void setRead(Long read) {
        this.read = read;
    }
}

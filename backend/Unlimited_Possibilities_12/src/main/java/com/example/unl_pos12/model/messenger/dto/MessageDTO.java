package com.example.unl_pos12.model.messenger.dto;

public class MessageDTO {
    private Long id;
    private String content;
    private UserDTO sender; // Предположим, у вас есть UserDTO

    // Конструкторы
    public MessageDTO() {}

    public MessageDTO(Long id, String content, UserDTO sender) {
        this.id = id;
        this.content = content;
        this.sender = sender;
    }

    // Getters и Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public UserDTO getSender() { return sender; }
    public void setSender(UserDTO sender) { this.sender = sender; }
}

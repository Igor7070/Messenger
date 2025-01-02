package com.example.unl_pos12.model.messenger.dto;

public class UserDTO {
    private Long id;
    private String username;

    // Конструкторы
    public UserDTO() {}

    public UserDTO(Long id, String username) {
        this.id = id;
        this.username = username;
    }

    // Getters и Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}

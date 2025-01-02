package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chats")
public class ChatController {
    @Autowired
    private ChatService chatService;

    @PostMapping
    public Chat createChat(@RequestBody Chat chat) {
        System.out.println("Chat " + chat.getName() + " created.");
        return chatService.createChat(chat);
    }

    @GetMapping
    public List<Chat> getAllChats() {
        return chatService.getAllChats();
    }

    @DeleteMapping("/{id}")
    public void deleteChat(@PathVariable Long id) {
        chatService.deleteChat(id);
    }
}

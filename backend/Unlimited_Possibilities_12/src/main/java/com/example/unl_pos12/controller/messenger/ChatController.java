package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chats")
public class ChatController {
    @Autowired
    private ChatService chatService;

    @PostMapping
    public Chat createChat(@RequestBody Chat chat) {
        System.out.println("Creating chat: " + chat.getName() + ", isPrivate: " + chat.isPrivate());
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

    @GetMapping("/{id}/messages")
    public List<Message> getMessagesByChatId(@PathVariable Long id) {
        return chatService.getMessagesByChatId(id);
    }

    @GetMapping("/{id}")
    public Chat getChatById(@PathVariable Long id) {
        return chatService.getChatById(id);
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkChatExists(@RequestParam String name1, @RequestParam String name2) {
        Long chatId = chatService.findChatByUsers(name1, name2);
        Map<String, Object> response = new HashMap<>();
        response.put("chatId", chatId != null ? chatId : -1); // Возвращаем -1, если чат не найден
        return ResponseEntity.ok(response);
    }

    @GetMapping("/lastChatId")
    public ResponseEntity<Long> getLastChatId() {
        Long lastChatId = chatService.getLastChatId();
        return ResponseEntity.ok(lastChatId);
    }
}

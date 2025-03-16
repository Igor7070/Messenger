package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.service.ChatService;
import com.example.unl_pos12.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
    @Autowired
    private UserService userService;

    @PostMapping
    public ResponseEntity<?> createChat(@RequestBody Chat chat) {
        System.out.println("Creating chat: " + chat.getName() + ", isPrivate: " + chat.isPrivate());

        try {
            // Создаем чат
            Chat createdChat = chatService.createChat(chat);

            // Если чат приватный, добавляем его обоим пользователям
            if (chat.isPrivate()) {
                String[] usernames = chat.getName().split("_"); // Предполагаем, что имя чата состоит из "Имя1_Имя2"
                if (usernames.length == 2) {
                    User user1 = userService.getUserByUsername(usernames[0]);
                    User user2 = userService.getUserByUsername(usernames[1]);

                    // Добавляем чат в список приватных чатов пользователей
                    user1.getPrivateChats().add(createdChat);
                    user2.getPrivateChats().add(createdChat);

                    // Обновляем пользователей
                    userService.updateUser(user1);
                    userService.updateUser(user2);
                } else {
                    System.out.println("Invalid chat name format: " + chat.getName());
                }
            }

            System.out.println("Chat " + chat.getName() + " created.");
            System.out.println("ChatId = " + createdChat.getId());
            return ResponseEntity.ok(createdChat);
        } catch (RuntimeException e) {
            // Обработка ошибки, когда чат с таким именем уже существует
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
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

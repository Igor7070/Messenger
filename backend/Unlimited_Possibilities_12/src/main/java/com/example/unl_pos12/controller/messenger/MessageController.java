package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.NotificationRequest;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.UserRepository;
import com.example.unl_pos12.service.MessageService;
import com.example.unl_pos12.service.WebSocketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    @Autowired
    private MessageService messageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebSocketService webSocketService; // Сервис для отправки уведомлений через WebSocket

    @GetMapping
    public List<Message> getMessages() {
        return messageService.getAllMessages();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Message> getMessageStatus(@PathVariable Long id) {
        Message message = messageService.findById(id);
        if (message != null) {
            return ResponseEntity.ok(message);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/search")
    public ResponseEntity<Message> getMessageByContent(@RequestParam String content, @RequestParam Long senderId, @RequestParam Long chatId) {
        List<Message> messages = (List<Message>) messageService.findByContentAndSenderAndChat(content, senderId, chatId);

        if (messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Находим сообщение с самой поздней меткой времени
        Message latestMessage = messages.stream()
                .max(Comparator.comparing(Message::getTimestamp))
                .orElse(null);

        return ResponseEntity.ok(latestMessage);
    }

    @PostMapping
    public Message sendMessage(@RequestBody Message message) {
        // Загрузка пользователя из базы данных
        User sender = userRepository.findById(message.getSender().getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        message.setSender(sender); // Установка существующего пользователя как отправителя
        //message.setTimestamp(LocalDateTime.now()); // Установка временной метки
        message.setTimestamp(ZonedDateTime.now());
        return messageService.saveMessage(message);
    }

    @PutMapping("/{id}")
    public Message editMessage(@PathVariable Long id, @RequestBody Message message) {
        message.setId(id);
        return messageService.updateMessage(message);
    }

    @DeleteMapping("/{id}")
    public void deleteMessage(@PathVariable Long id) {
        messageService.deleteMessage(id);
    }

    @PostMapping("/notify")
    public ResponseEntity<String> sendNotification(@RequestBody NotificationRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("Recipient not found"));

        User recipient = userRepository.findById(request.getRecipientId())
                .orElseThrow(() -> new RuntimeException("Recipient not found"));

        webSocketService.sendNotification(user.getId(), recipient.getId(), request.getContent(),
                request.getChatId(), request.getIsSecret(), request.getMessageType());

        return ResponseEntity.ok("Notification sent");
    }

    @GetMapping("/chats/{chatId}/messages")
    public ResponseEntity<List<Message>> getMessagesByChatId(@PathVariable Long chatId) {
        List<Message> messages = messageService.findByChatId(chatId);
        return ResponseEntity.ok(messages);
    }
}

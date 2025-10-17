package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.*;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.repo.UserRepository;
import com.example.unl_pos12.service.MessageService;
import com.example.unl_pos12.service.WebSocketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private ChatRepository chatRepository;

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

        // Проверка, секретный ли чат
        String notificationContent;
        Chat chat = chatRepository.findById(request.getChatId()).orElse(null);
        boolean isSecretChat = chat != null && chat.getIsSecret() != null && chat.getIsSecret();
        if (isSecretChat) {
            notificationContent = "New secret message";
        } else if (request.getContent() == null || request.getContent().isEmpty()) {
            notificationContent = "New audio message";
        } else {
            notificationContent = request.getContent();
        }

        webSocketService.sendNotification(user.getId(), recipient.getId(), notificationContent, request.getChatId());

        return ResponseEntity.ok("Notification sent");
    }

    @GetMapping("/chats/{chatId}/messages")
    public ResponseEntity<List<Message>> getMessagesByChatId(@PathVariable Long chatId) {
        List<Message> messages = messageService.findByChatId(chatId);
        return ResponseEntity.ok(messages);
    }

    // [ADD] Новый endpoint для загрузки зашифрованных файлов
    @PostMapping("/upload_encrypted_file")
    public ResponseEntity<String> uploadEncryptedFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("nonce") String nonce,
            @RequestParam("publicKeyId") Long publicKeyId,
            @RequestParam("messageId") Long messageId) {
        Message message = messageService.getMessageById(messageId);
        if (message == null) {
            throw new RuntimeException("Message not found for id: " + messageId);
        }
        String fileUrl = messageService.uploadEncryptedFile(file, nonce, publicKeyId, message);
        return ResponseEntity.ok(fileUrl);
    }

    // [ADD] Endpoint для получения метаданных файла
    @GetMapping("/file_metadata/{messageId}")
    public ResponseEntity<FileMetadata> getFileMetadata(@PathVariable Long messageId) {
        FileMetadata fileMetadata = messageService.getFileMetadataByMessageId(messageId);
        if (fileMetadata != null) {
            return ResponseEntity.ok(fileMetadata);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}

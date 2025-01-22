package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.UserRepository;
import com.example.unl_pos12.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    @Autowired
    private MessageService messageService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public List<Message> getMessages() {
        return messageService.getAllMessages();
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
}

package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.repo.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MessageService {
    @Autowired
    private MessageRepository messageRepository;

    public List<Message> getAllMessages() {
        return messageRepository.findAll();
    }

    public Message saveMessage(Message message) {
        message.setTimestamp(LocalDateTime.now()); // Устанавливаем временную метку
        return messageRepository.save(message);
    }
}

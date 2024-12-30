package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.repo.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public Message updateMessage(Message message) {
        return messageRepository.save(message);
    }

    public void deleteMessage(Long id) {
        messageRepository.deleteById(id);
    }

    public Message saveMessage(Message message, MultipartFile file) {
        if (file != null && !file.isEmpty()) {
            String fileUrl = uploadFile(file);
            message.setFileUrl(fileUrl);
        }
        message.setTimestamp(LocalDateTime.now());
        return messageRepository.save(message);
    }

    /*private String uploadFile(MultipartFile file) {
        String uploadDir = "uploads/";

        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = Paths.get(uploadDir + filename);

        try {
            Files.copy(file.getInputStream(), filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload file", e);
        }

        return "/uploads/" + filename;
    }*/

    private String uploadFile(MultipartFile file) {
        String uploadDir = "uploads/";

        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = Paths.get(uploadDir + filename);

        try {
            Files.copy(file.getInputStream(), filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload file", e);
        }

        // Возвращаем полный URL
        //return "/api/files/download/" + filename; // Обновите этот путь в соответствии с вашим маршрутом
        // Формируем полный URL
        String serverUrl = "https://igor7070.github.io/Messenger"; // Замените на ваш домен или IP-адрес
        return serverUrl + "/api/files/download/" + filename;
    }

    public Message getMessageById(Long id) {
        return messageRepository.findById(id).orElse(null);
    }
}

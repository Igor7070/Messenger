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
        System.out.println("Saving message: " + message.getContent() + " for chatId: " + message.getChat().getId());
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

    /*public Message markAsRead(Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));
        message.setRead(true); // Устанавливаем статус прочтения
        return messageRepository.save(message);
    }*/

    public String uploadFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("Uploaded file is empty");
        }

        // Проверка типа файла
        if (!isValidFileType(file.getContentType())) {
            throw new RuntimeException("Unsupported file type: " + file.getContentType());
        }

        String uploadDir = "uploads/";
        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String originalFilename = file.getOriginalFilename();
        // Преобразуем имя файла на латиницу
        String transliteratedFilename = transliterate(originalFilename);
        // Заменяем небезопасные символы
        String safeFilename = transliteratedFilename.replaceAll("[^\\w.-]", "_");
        String filename = System.currentTimeMillis() + "_" + safeFilename;
        Path filePath = Paths.get(uploadDir + filename);

        try {
            Files.copy(file.getInputStream(), filePath);
        } catch (IOException e) {
            System.out.println("Error while uploading file: " + e.getMessage());
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }

        String serverUrl = "https://unlimitedpossibilities12.org";
        return serverUrl + "/api/files/download/" + filename;
    }

    private boolean isValidFileType(String contentType) {
        return contentType.equals("text/plain") ||
                contentType.equals("image/jpeg") ||
                contentType.equals("image/png") ||
                contentType.equals("application/msword") ||
                contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"); // для .docx
    }

    private String transliterate(String input) {
        String[][] mapping = {
                {"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"ґ", "g"}, {"д", "d"},
                {"е", "e"}, {"є", "ye"}, {"ж", "zh"}, {"з", "z"}, {"и", "y"}, {"і", "i"},
                {"ї", "yi"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"},
                {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"},
                {"ф", "f"}, {"х", "kh"}, {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "shch"},
                {"ь", ""}, {"ю", "yu"}, {"я", "ya"},
                {"А", "A"}, {"Б", "B"}, {"В", "V"}, {"Г", "G"}, {"Ґ", "G"}, {"Д", "D"},
                {"Е", "E"}, {"Є", "Ye"}, {"Ж", "Zh"}, {"З", "Z"}, {"И", "Y"}, {"І", "I"},
                {"Ї", "Yi"}, {"Й", "Y"}, {"К", "K"}, {"Л", "L"}, {"М", "M"}, {"Н", "N"},
                {"О", "O"}, {"П", "P"}, {"Р", "R"}, {"С", "S"}, {"Т", "T"}, {"У", "U"},
                {"Ф", "F"}, {"Х", "Kh"}, {"Ц", "Ts"}, {"Ч", "Ch"}, {"Ш", "Sh"}, {"Щ", "Shch"},
                {"Ь", ""}, {"Ю", "Yu"}, {"Я", "Ya"}
        };

        for (String[] pair : mapping) {
            input = input.replace(pair[0], pair[1]);
        }
        return input;
    }

    public Message getMessageById(Long id) {
        return messageRepository.findById(id).orElse(null);
    }
}

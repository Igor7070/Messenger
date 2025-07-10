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
import java.time.ZonedDateTime;
import java.util.List;

@Service
public class MessageService {
    @Autowired
    private MessageRepository messageRepository;

    public List<Message> getAllMessages() {
        return messageRepository.findAll();
    }

    public Message findById(Long id) {
        return messageRepository.findById(id).orElse(null);
    }

    public List<Message> findByContentAndSenderAndChat(String content, Long senderId, Long chatId) {
        return messageRepository.findByContentAndSenderIdAndChatId(content, senderId, chatId);
    }

    public Message saveMessage(Message message) {
        //message.setTimestamp(LocalDateTime.now()); // Устанавливаем временную метку
        message.setTimestamp(ZonedDateTime.now());
        System.out.println("Saving message: " + message.getContent() + " for chatId: " + message.getChat().getId());
        return messageRepository.save(message);
    }

    public Message updateMessage(Message message) {
        return messageRepository.save(message);
    }

    public void deleteMessage(Long id) {
        messageRepository.deleteById(id);
    }

    // Изменённый метод
    public Message saveMessage(Message message, MultipartFile file) {
        if (file != null && !file.isEmpty()) {
            String fileUrl = uploadFile(file);
            message.setFileUrl(fileUrl);
            // Если file передан, устанавливаем messageType только если он не указан
            if (message.getMessageType() == null) {
                String contentType = file.getContentType();
                if (contentType != null && (contentType.equals("audio/mpeg") ||
                        contentType.equals("audio/wav") ||
                        contentType.equals("audio/webm"))) {
                    message.setMessageType("audio");
                } else {
                    message.setMessageType("file");
                }
            }
        }

        message.setTimestamp(ZonedDateTime.now());
        System.out.println("Saving message: type=" + message.getMessageType() + ", content=" + message.getContent() + ", chatId=" + message.getChat().getId());
        return messageRepository.save(message);
    }

    public Message markAsRead(Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));
        message.setRead_status(true); // Устанавливаем статус прочтения
        return messageRepository.save(message);
    }

    public String uploadFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("Uploaded file is empty");
        }

        if (file.getOriginalFilename() == null) {
            throw new RuntimeException("Filename is null");
        }

        // Проверка размера файла (100 МБ = 100 * 1024 * 1024 байт)
        long maxFileSize = 100 * 1024 * 1024;
        if (file.getSize() > maxFileSize) {
            System.out.println("File size exceeds 100 MB: size=" + file.getSize());
            throw new RuntimeException("File size exceeds 100 MB");
        }

        String uploadDir = "uploads/";
        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String originalFilename = file.getOriginalFilename();

        // --- НАЧАЛО ДОБАВЛЕНИЯ ---
        String contentType = file.getContentType();
        System.out.println("Uploading file: original name=" + originalFilename + ", contentType=" + contentType);
        if (originalFilename.endsWith(".webm")) {
            contentType = "audio/webm"; // Явно устанавливаем Content-Type для .webm
            System.out.println("Forcing Content-Type to audio/webm for file: " + originalFilename);
        }
        // --- КОНЕЦ ДОБАВЛЕНИЯ ---

        // Преобразуем имя файла на латиницу
        String transliteratedFilename = transliterate(originalFilename);
        // Заменяем небезопасные символы
        String safeFilename = transliteratedFilename.replaceAll("[^\\w.-]", "_");
        String filename = System.currentTimeMillis() + "_" + safeFilename;
        Path filePath = Paths.get(uploadDir + filename);

        try {
            System.out.println("Uploading file: name=" + originalFilename + ", size=" + file.getSize() + ", type=" + file.getContentType());
            Files.copy(file.getInputStream(), filePath);
            System.out.println("File uploaded successfully: path=" + filePath);
        } catch (IOException e) {
            System.out.println("Error while uploading file: " + e.getMessage());
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }

        String serverUrl = "https://unlimitedpossibilities12.org";
        return serverUrl + "/api/files/download/" + filename;
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

    public List<Message> findByChatId(Long chatId) {
        return messageRepository.findByChatId(chatId);
    }

    public Message findByFileUrl(String fileUrl) {
        return messageRepository.findByFileUrl(fileUrl);
    }
}

package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.FileMetadata;
import com.example.unl_pos12.model.messenger.FileMetadataSelf;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.PublicKeyHistory;
import com.example.unl_pos12.repo.FileMetadataRepository;
import com.example.unl_pos12.repo.FileMetadataSelfRepository;
import com.example.unl_pos12.repo.MessageRepository;
import com.example.unl_pos12.repo.PublicKeyHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessageService {
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private FileMetadataRepository fileMetadataRepository; // [ADD] Внедряем FileMetadataRepository
    @Autowired
    private PublicKeyHistoryRepository publicKeyHistoryRepository; // [CHANGE]
    @Autowired
    private WebSocketService webSocketService; // Добавляем зависимость для WebSocket
    @Autowired
    private FileMetadataSelfRepository fileMetadataSelfRepository;

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
            // Если file передан, устанавливаем messageType и contentType
            if (message.getMessageType() == null) {
                String contentType = file.getContentType();
                if (file.getOriginalFilename().endsWith(".webm")) {
                    contentType = "audio/webm;codecs=opus"; // Переопределяем для .webm
                }
                message.setContentType(contentType); // Сохраняем contentType
                if (contentType != null && (contentType.equals("audio/mpeg") ||
                        contentType.equals("audio/wav") ||
                        contentType.equals("audio/webm") ||
                        contentType.equals("audio/webm;codecs=opus"))) {
                    message.setMessageType("audio");
                } else {
                    message.setMessageType("file");
                }
            }
        }

        message.setTimestamp(ZonedDateTime.now());

        // Проверка для секретных чатов
        if (message.getChat() != null && message.getChat().getIsSecret() != null && message.getChat().getIsSecret()) {
            if ("text".equals(message.getMessageType())) {
                if (message.getEncryptedContent() == null || message.getNonce() == null) {
                    throw new RuntimeException("Encrypted content or nonce is missing for secret chat");
                }
                message.setContent(null);
                message.setTranslatedContent(null);
                message.setTranscribedContent(null);
            } else if (!"audio".equals(message.getMessageType()) && !"file".equals(message.getMessageType())) {
                throw new RuntimeException("Invalid message type for secret chat: " + message.getMessageType());
            }
        } else {
            message.setEncryptedContent(null);
            message.setNonce(null);
            if (message.getContent() == null && message.getFileUrl() == null) {
                throw new RuntimeException("Content or fileUrl is required for non-secret chat");
            }
        }

        System.out.println("Saving message: type=" + message.getMessageType() +
                ", content=" + message.getContent() +
                ", encryptedContent=" + message.getEncryptedContent() +
                ", chatId=" + message.getChat().getId() +
                ", isSecret=" + (message.getChat() != null ? message.getChat().getIsSecret() : false) +
                ", contentType=" + message.getContentType());
        return messageRepository.save(message);
    }

    // [ADD] Новый метод для сообщений с зашифрованными файлами
    public Message saveEncryptedFileMessage(Message message, MultipartFile encryptedFile, String fileNonce) {
        if (encryptedFile == null || encryptedFile.isEmpty()) {
            throw new RuntimeException("Uploaded encrypted file is empty");
        }
        if (encryptedFile.getOriginalFilename() == null) {
            throw new RuntimeException("Filename is null");
        }
        if (fileNonce == null) {
            throw new RuntimeException("Nonce is missing for encrypted file");
        }

        // Проверка для секретных чатов
        if (message.getChat() == null || message.getChat().getIsSecret() == null || !message.getChat().getIsSecret()) {
            throw new RuntimeException("Encrypted file messages are only allowed in secret chats");
        }
        if (message.getEncryptedContent() == null || message.getNonce() == null) {
            throw new RuntimeException("Encrypted content or nonce is missing for secret chat file message");
        }

        // Проверка наличия активного публичного ключа
        PublicKeyHistory publicKeyHistory = publicKeyHistoryRepository.findByUserIdAndValidUntilIsNull(message.getSender().getId());
        if (publicKeyHistory == null) {
            System.out.println("No active public key found for user: " + message.getSender().getId());
            throw new RuntimeException("No active public key found for user: " + message.getSender().getId());
        }

        // Загрузка файла
        String fileName = System.currentTimeMillis() + "_encrypted_" + transliterate(encryptedFile.getOriginalFilename());
        String filePath = "uploads/encrypted/" + fileName;
        String fullFileUrl = "https://unlimitedpossibilities12.org/api/files/download/encrypted/" + fileName;
        System.out.println("Uploading encrypted file: original name=" + encryptedFile.getOriginalFilename() + ", contentType=" + encryptedFile.getContentType());

        try {
            File dest = new File(System.getProperty("user.dir") + "/" + filePath);
            dest.getParentFile().mkdirs();
            encryptedFile.transferTo(dest);
            System.out.println("Encrypted file uploaded successfully: path=" + filePath);
        } catch (IOException e) {
            System.out.println("Failed to upload encrypted file: " + e.getMessage());
            throw new RuntimeException("Failed to upload encrypted file: " + e.getMessage());
        }

        // Установка fileUrl и других полей в сообщении
        message.setFileUrl(fullFileUrl);
        if (message.getMessageType() == null) {
            String contentType = encryptedFile.getContentType();
            if (encryptedFile.getOriginalFilename().endsWith(".webm")) {
                contentType = "audio/webm;codecs=opus";
            }
            message.setContentType(contentType);
            message.setMessageType(contentType != null && contentType.startsWith("audio/") ? "audio" : "file");
        }
        message.setContent(null);
        message.setTranslatedContent(null);
        message.setTranscribedContent(null);
        message.setTimestamp(ZonedDateTime.now());

        // Сохранение сообщения
        Message savedMessage = messageRepository.save(message);
        System.out.println("Saved message with fileUrl: " + savedMessage.getFileUrl() + ", messageId: " + savedMessage.getId());

        // Сохранение метаданных файла
        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setFileUrl(fullFileUrl);
        fileMetadata.setNonce(fileNonce);
        fileMetadata.setMessage(savedMessage);
        fileMetadata.setPublicKeyId(publicKeyHistory.getId()); // Оставляем publicKeyId, как указано
        fileMetadataRepository.save(fileMetadata);
        System.out.println("Saved file metadata: fileUrl=" + fullFileUrl + ", messageId=" + savedMessage.getId() + ", nonce=" + fileNonce + ", publicKeyId=" + publicKeyHistory.getId());

        // Отправка обновлённого сообщения через WebSocket
        //webSocketService.sendMessageUpdate(savedMessage.getChat().getId(), savedMessage);
        System.out.println("Sent WebSocket update for messageId: " + savedMessage.getId() + ", chatId: " + savedMessage.getChat().getId());

        return savedMessage;
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
        String contentType = file.getContentType();
        System.out.println("Uploading file: original name=" + originalFilename + ", contentType=" + contentType);

        // Преобразуем имя файла на латиницу
        String transliteratedFilename = transliterate(originalFilename);
        // Заменяем небезопасные символы
        String safeFilename = transliteratedFilename.replaceAll("[^\\w.-]", "_");
        String filename = System.currentTimeMillis() + "_" + safeFilename;
        Path filePath = Paths.get(uploadDir, filename);

        try {
            System.out.println("Uploading file: name=" + originalFilename + ", size=" + file.getSize() + ", type=" + contentType);
            Files.copy(file.getInputStream(), filePath);
            System.out.println("File uploaded successfully: path=" + filePath);
        } catch (IOException e) {
            System.out.println("Error while uploading file: " + e.getMessage());
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }

        String serverUrl = "https://unlimitedpossibilities12.org";
        return serverUrl + "/api/files/download/" + filename;
    }

    // [ADD] Новый метод для загрузки зашифрованных файлов
    /*public String uploadEncryptedFile(MultipartFile encryptedFile, String fileNonce, Message message) { // [CHANGE] Убрали filePublicKeyId
        if (encryptedFile.isEmpty()) {
            throw new RuntimeException("Uploaded encrypted file is empty");
        }
        if (encryptedFile.getOriginalFilename() == null) {
            throw new RuntimeException("Filename is null");
        }
        if (fileNonce == null) {
            throw new RuntimeException("Nonce is missing for encrypted file");
        }

        // [CHANGE] Проверяем наличие активного публичного ключа
        PublicKeyHistory publicKeyHistory = publicKeyHistoryRepository.findByUserIdAndValidUntilIsNull(message.getSender().getId());
        if (publicKeyHistory == null) {
            throw new RuntimeException("No active public key found for user: " + message.getSender().getId());
        }

        long maxFileSize = 100 * 1024 * 1024;
        if (encryptedFile.getSize() > maxFileSize) {
            System.out.println("Encrypted file size exceeds 100 MB: size=" + encryptedFile.getSize());
            throw new RuntimeException("Encrypted file size exceeds 100 MB");
        }

        String uploadDir = "Uploads/encrypted/";
        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String originalFilename = encryptedFile.getOriginalFilename();
        String contentType = encryptedFile.getContentType();
        System.out.println("Uploading encrypted file: original name=" + originalFilename + ", contentType=" + contentType);

        String transliteratedFilename = transliterate(originalFilename);
        String safeFilename = transliteratedFilename.replaceAll("[^\\w.-]", "_");
        String filename = System.currentTimeMillis() + "_encrypted_" + safeFilename;
        Path filePath = Paths.get(uploadDir, filename);

        try {
            System.out.println("Uploading encrypted file: name=" + originalFilename + ", size=" + encryptedFile.getSize() + ", type=" + contentType);
            Files.copy(encryptedFile.getInputStream(), filePath);
            System.out.println("Encrypted file uploaded successfully: path=" + filePath);
        } catch (IOException e) {
            System.out.println("Error while uploading encrypted file: " + e.getMessage());
            throw new RuntimeException("Failed to upload encrypted file: " + e.getMessage(), e);
        }

        String serverUrl = "https://unlimitedpossibilities12.org";
        String fileUrl = serverUrl + "/api/files/download/encrypted/" + filename;

        // [CHANGE] Сохраняем метаданные без publicKeyId
        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setFileUrl(fileUrl);
        fileMetadata.setNonce(fileNonce);
        fileMetadata.setMessage(message);
        fileMetadataRepository.save(fileMetadata);
        System.out.println("Saved file metadata: fileUrl=" + fileUrl + ", nonce=" + fileNonce);

        return fileUrl;
    }*/

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

    // [ADD] Метод для получения метаданных файла
    public FileMetadata getFileMetadataByMessageId(Long messageId) {
        return fileMetadataRepository.findByMessageId(messageId);
    }

    // [ADD] Новый метод для получения расширенных метаданных файла
    public Map<String, Object> getExtendedFileMetadataByMessageId(Long messageId) {
        FileMetadata fileMetadata = fileMetadataRepository.findByMessageId(messageId); // [CHANGE] Убрали .orElse(null)
        FileMetadataSelf fileMetadataSelf = fileMetadataSelfRepository.findByMessageId(messageId); // [CHANGE] Убрали .orElse(null)
        Message message = messageRepository.findById(messageId).orElse(null); // Оставляем, так как findById возвращает Optional

        if (fileMetadata == null || message == null) {
            return null;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", fileMetadata.getId());
        response.put("fileUrl", fileMetadata.getFileUrl());
        response.put("nonce", fileMetadata.getNonce());
        response.put("publicKeyId", fileMetadata.getPublicKeyId());
        response.put("message", message);
        response.put("contentType", message.getContentType());
        if (fileMetadataSelf != null) {
            response.put("fileUrlSelf", fileMetadataSelf.getFileUrlSelf());
            response.put("fileName", fileMetadataSelf.getFileName());
        } else {
            String fileName = "file"; // Запасное значение
            response.put("fileName", fileName);
            response.put("fileUrlSelf", fileMetadata.getFileUrl() != null
                    ? fileMetadata.getFileUrl().replace("/encrypted/", "/encrypted_self/").replace("_encrypted_blob", "_encrypted_self_" + fileName)
                    : null);
        }
        return response;
    }

    // [ADD] Метод для сохранения собственных зашифрованных файлов (без изменений от предыдущего ответа)
    public Message saveEncryptedFileSelfMessage(Message message, MultipartFile encryptedFile, String fileNonce, String originalFileName) {
        if (encryptedFile == null || encryptedFile.isEmpty()) {
            throw new RuntimeException("Uploaded encrypted file is empty");
        }
        if (originalFileName == null) {
            throw new RuntimeException("Filename is null");
        }
        if (fileNonce == null) {
            throw new RuntimeException("Nonce is missing for encrypted file");
        }

        // Проверка для секретных чатов
        if (message.getChat() == null || message.getChat().getIsSecret() == null || !message.getChat().getIsSecret()) {
            throw new RuntimeException("Encrypted file messages are only allowed in secret chats");
        }
        if (message.getEncryptedContent() == null || message.getNonce() == null) {
            throw new RuntimeException("Encrypted content or nonce is missing for secret chat file message");
        }

        // Проверка наличия активного публичного ключа
        PublicKeyHistory publicKeyHistory = publicKeyHistoryRepository.findByUserIdAndValidUntilIsNull(message.getSender().getId());
        if (publicKeyHistory == null) {
            System.out.println("No active public key found for user: " + message.getSender().getId());
            throw new RuntimeException("No active public key found for user: " + message.getSender().getId());
        }

        // Загрузка файла
        String transliteratedFilename = transliterate(originalFileName);
        String safeFilename = transliteratedFilename.replaceAll("[^\\w.-]", "_");
        String fileName = message.getId() + "_encrypted_self_" + safeFilename;
        String filePath = "Uploads/encrypted_self/" + fileName;
        String fullFileUrlSelf = "https://unlimitedpossibilities12.org/api/files/download/encrypted_self/" + fileName;
        System.out.println("Uploading encrypted self file: original name=" + originalFileName + ", contentType=" + encryptedFile.getContentType());

        try {
            File dest = new File(System.getProperty("user.dir") + "/" + filePath);
            dest.getParentFile().mkdirs();
            encryptedFile.transferTo(dest);
            System.out.println("Encrypted self file uploaded successfully: path=" + filePath);
        } catch (IOException e) {
            System.out.println("Failed to upload encrypted self file: " + e.getMessage());
            throw new RuntimeException("Failed to upload encrypted self file: " + e.getMessage());
        }

        // Установка полей в сообщении
        String contentType = encryptedFile.getContentType();
        if (originalFileName.endsWith(".webm")) {
            contentType = "audio/webm;codecs=opus";
        }
        message.setContentType(contentType);
        message.setMessageType(contentType != null && contentType.startsWith("audio/") ? "audio" : "file");
        message.setContent(null);
        message.setTranslatedContent(null);
        message.setTranscribedContent(null);
        message.setTimestamp(ZonedDateTime.now());

        // Сохранение сообщения
        Message savedMessage = messageRepository.save(message);
        System.out.println("Saved message with fileUrl: " + savedMessage.getFileUrl() + ", messageId=" + savedMessage.getId());

        // Сохранение метаданных для получателя
        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setFileUrl(savedMessage.getFileUrl());
        fileMetadata.setNonce(fileNonce);
        fileMetadata.setMessage(savedMessage);
        fileMetadata.setPublicKeyId(publicKeyHistory.getId());
        fileMetadataRepository.save(fileMetadata);
        System.out.println("Saved file metadata: fileUrl=" + savedMessage.getFileUrl() + ", messageId=" + savedMessage.getId() + ", nonce=" + fileNonce + ", publicKeyId=" + publicKeyHistory.getId());

        // Проверка и удаление существующих записей в file_metadata_self
        FileMetadataSelf existingFileMetadataSelf = fileMetadataSelfRepository.findByMessageId(savedMessage.getId());
        if (existingFileMetadataSelf != null) {
            System.out.println("FileMetadataSelf already exists for messageId=" + savedMessage.getId() + ", deleting...");
            fileMetadataSelfRepository.deleteByMessageId(savedMessage.getId());
        }

        // Сохранение метаданных для отправителя
        FileMetadataSelf fileMetadataSelf = new FileMetadataSelf();
        fileMetadataSelf.setFileUrlSelf(fullFileUrlSelf);
        fileMetadataSelf.setFileName(safeFilename);
        fileMetadataSelf.setNonce(fileNonce);
        fileMetadataSelf.setMessage(savedMessage);
        fileMetadataSelf.setPublicKeyId(publicKeyHistory.getId());
        fileMetadataSelfRepository.save(fileMetadataSelf);
        System.out.println("Saved self file metadata: fileUrlSelf=" + fullFileUrlSelf + ", fileName=" + safeFilename + ", messageId=" + savedMessage.getId());

        return savedMessage;
    }
}

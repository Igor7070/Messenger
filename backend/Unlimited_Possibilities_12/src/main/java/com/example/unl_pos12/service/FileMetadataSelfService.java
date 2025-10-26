package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.FileMetadataSelf;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.PublicKeyHistory;
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

@Service
public class FileMetadataSelfService {
    @Autowired
    private FileMetadataSelfRepository fileMetadataSelfRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private PublicKeyHistoryRepository publicKeyHistoryRepository;

    public FileMetadataSelf saveEncryptedFileSelf(Long messageId, MultipartFile fileSelf, String fileName, String nonce) {
        if (fileSelf == null || fileSelf.isEmpty()) {
            throw new RuntimeException("Uploaded encrypted file for self is empty");
        }
        if (fileName == null) {
            throw new RuntimeException("Filename is null");
        }
        if (nonce == null) {
            throw new RuntimeException("Nonce is missing for encrypted file");
        }

        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found for id: " + messageId));

        // Проверка для секретных чатов
        if (message.getChat() == null || !message.getChat().getIsSecret()) {
            throw new RuntimeException("Encrypted file messages are only allowed in secret chats");
        }

        // Проверка публичного ключа отправителя
        PublicKeyHistory publicKeyHistory = publicKeyHistoryRepository.findByUserIdAndValidUntilIsNull(message.getSender().getId());
        if (publicKeyHistory == null) {
            throw new RuntimeException("No active public key found for user: " + message.getSender().getId());
        }

        // Загрузка файла
        String uploadDir = "Uploads/encrypted_self/";
        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String safeFilename = System.currentTimeMillis() + "_encrypted_self_" + fileName.replaceAll("[^\\w.-]", "_");
        Path filePath = Paths.get(uploadDir, safeFilename);
        String fileUrlSelf = "https://unlimitedpossibilities12.org/api/files/download/encrypted_self/" + safeFilename;

        try {
            Files.copy(fileSelf.getInputStream(), filePath);
            System.out.println("Encrypted file for self uploaded successfully: path=" + filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload encrypted file for self: " + e.getMessage());
        }

        // Сохранение метаданных
        FileMetadataSelf fileMetadataSelf = new FileMetadataSelf();
        fileMetadataSelf.setFileUrlSelf(fileUrlSelf);
        fileMetadataSelf.setFileName(fileName); // Сохраняем оригинальное имя
        fileMetadataSelf.setNonce(nonce);
        fileMetadataSelf.setPublicKeyId(publicKeyHistory.getId());
        fileMetadataSelf.setMessage(message);

        fileMetadataSelfRepository.save(fileMetadataSelf);
        System.out.println("Saved file metadata for self: fileUrlSelf=" + fileUrlSelf + ", fileName=" + fileName + ", messageId=" + messageId);

        return fileMetadataSelf;
    }

    public FileMetadataSelf getFileMetadataSelfByMessageId(Long messageId) {
        return fileMetadataSelfRepository.findByMessageId(messageId);
    }
}

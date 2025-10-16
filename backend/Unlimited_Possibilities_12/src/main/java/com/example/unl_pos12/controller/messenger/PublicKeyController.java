package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.PublicKey;
import com.example.unl_pos12.model.messenger.PublicKeyHistory;
import com.example.unl_pos12.repo.PublicKeyHistoryRepository;
import com.example.unl_pos12.repo.PublicKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;

@RestController
@RequestMapping("/api/public_keys")
public class PublicKeyController {
    @Autowired
    private PublicKeyRepository publicKeyRepository;
    @Autowired
    private PublicKeyHistoryRepository publicKeyHistoryRepository; // [ADD] Внедрение PublicKeyHistoryRepository
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @GetMapping("/{userId}")
    public ResponseEntity<PublicKey> getPublicKey(@PathVariable Long userId) {
        System.out.println("Received GET /api/public_keys/" + userId);
        PublicKeyHistory publicKeyHistory = publicKeyHistoryRepository.findByUserIdAndValidUntilIsNull(userId);
        if (publicKeyHistory != null) {
            PublicKey publicKey = new PublicKey();
            publicKey.setUserId(userId);
            publicKey.setPublicKey(publicKeyHistory.getPublicKey());
            publicKey.setCreatedAt(publicKeyHistory.getCreatedAt());
            System.out.println("Found public key for userId: " + userId + ", key: " + publicKey.getPublicKey());
            return ResponseEntity.ok(publicKey);
        } else {
            System.out.println("Public key not found for userId: " + userId);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/history/{publicKeyId}")
    public ResponseEntity<PublicKey> getPublicKeyById(@PathVariable Long publicKeyId) { // [ADD] Новый эндпоинт для получения ключа по publicKeyId
        System.out.println("Received GET /api/public_keys/history/" + publicKeyId);
        PublicKeyHistory publicKeyHistory = publicKeyHistoryRepository.findById(publicKeyId).orElse(null); // [CHANGE] Используем Optional
        if (publicKeyHistory != null) {
            PublicKey publicKey = new PublicKey();
            publicKey.setUserId(publicKeyHistory.getUserId());
            publicKey.setPublicKey(publicKeyHistory.getPublicKey());
            publicKey.setCreatedAt(publicKeyHistory.getCreatedAt());
            System.out.println("Found public key for publicKeyId: " + publicKeyId + ", key: " + publicKey.getPublicKey());
            return ResponseEntity.ok(publicKey);
        } else {
            System.out.println("Public key not found for publicKeyId: " + publicKeyId);
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<PublicKey> savePublicKey(@RequestBody PublicKey publicKey) {
        System.out.println("Received POST /api/public_keys for userId: " + publicKey.getUserId() + ", key: " + publicKey.getPublicKey());

        // Проверяем существующий ключ в public_keys_history
        PublicKeyHistory existingKey = publicKeyHistoryRepository.findByUserIdAndValidUntilIsNull(publicKey.getUserId());
        if (existingKey != null) {
            existingKey.setValidUntil(ZonedDateTime.now());
            publicKeyHistoryRepository.save(existingKey);
            System.out.println("Marked previous key as invalid for userId: " + publicKey.getUserId());
        }

        // Сохраняем новый ключ в public_keys_history
        PublicKeyHistory newKeyHistory = new PublicKeyHistory();
        newKeyHistory.setUserId(publicKey.getUserId());
        newKeyHistory.setPublicKey(publicKey.getPublicKey());
        newKeyHistory.setCreatedAt(ZonedDateTime.now());
        newKeyHistory.setValidUntil(null);
        PublicKeyHistory savedKeyHistory = publicKeyHistoryRepository.save(newKeyHistory);
        System.out.println("Saved public key history for userId: " + savedKeyHistory.getUserId() + ", key: " + savedKeyHistory.getPublicKey());

        // Сохраняем в public_keys для обратной совместимости
        PublicKey savedPublicKey = new PublicKey();
        savedPublicKey.setUserId(publicKey.getUserId());
        savedPublicKey.setPublicKey(publicKey.getPublicKey());
        savedPublicKey.setCreatedAt(ZonedDateTime.now());
        publicKeyRepository.save(savedPublicKey);

        // Отправляем уведомление через WebSocket
        messagingTemplate.convertAndSend("/topic/publicKey/" + savedPublicKey.getUserId(), savedPublicKey);
        System.out.println("Sent WebSocket notification to /topic/publicKey/" + savedPublicKey.getUserId() + ": " + savedPublicKey.getPublicKey());

        return ResponseEntity.ok(savedPublicKey);
    }
}

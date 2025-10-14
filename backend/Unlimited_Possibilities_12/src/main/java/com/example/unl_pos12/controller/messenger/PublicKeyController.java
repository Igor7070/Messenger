package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.PublicKey;
import com.example.unl_pos12.repo.PublicKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public_keys")
public class PublicKeyController {
    @Autowired
    private PublicKeyRepository publicKeyRepository;
    @Autowired
    private SimpMessagingTemplate messagingTemplate; // [ADD] Внедрение SimpMessagingTemplate

    @GetMapping("/{userId}")
    public ResponseEntity<PublicKey> getPublicKey(@PathVariable Long userId) {
        System.out.println("Received GET /api/public_keys/" + userId);
        PublicKey publicKey = publicKeyRepository.findByUserId(userId);
        if (publicKey != null) {
            System.out.println("Found public key for userId: " + userId + ", key: " + publicKey.getPublicKey());
            return ResponseEntity.ok(publicKey);
        } else {
            System.out.println("Public key not found for userId: " + userId);
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<PublicKey> savePublicKey(@RequestBody PublicKey publicKey) { // [CHANGE] Изменён возвращаемый тип на ResponseEntity<PublicKey>
        System.out.println("Received POST /api/public_keys for userId: " + publicKey.getUserId() + ", key: " + publicKey.getPublicKey());
        PublicKey savedPublicKey = publicKeyRepository.save(publicKey);
        System.out.println("Saved public key for userId: " + savedPublicKey.getUserId() + ", key: " + savedPublicKey.getPublicKey());

        // [ADD] Отправка уведомления через WebSocket
        messagingTemplate.convertAndSend("/topic/publicKey/" + savedPublicKey.getUserId(), savedPublicKey);
        System.out.println("Sent WebSocket notification to /topic/publicKey/" + savedPublicKey.getUserId() + ": " + savedPublicKey.getPublicKey());

        return ResponseEntity.ok(savedPublicKey); // [CHANGE] Возвращаем ResponseEntity
    }
}

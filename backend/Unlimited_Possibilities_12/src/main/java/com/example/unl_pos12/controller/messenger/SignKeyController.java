package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.PublicKey;
import com.example.unl_pos12.model.messenger.SignKey;
import com.example.unl_pos12.repo.SignKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;

/**
 * Ключи подписи (Ed25519) для защищённых звонков.
 * Формат запросов и ответов повторяет /api/public_keys, чтобы клиентам было
 * достаточно поменять адрес. В отличие от /api/public_keys, обновление ключа
 * здесь НЕ рассылается в /topic/publicKey/{userId} — этот топик слушает
 * секретный чат, и ему ключи звонков не нужны.
 */
@RestController
@RequestMapping("/api/sign_keys")
public class SignKeyController {
    @Autowired
    private SignKeyRepository signKeyRepository;

    @GetMapping("/{userId}")
    public ResponseEntity<PublicKey> getSignKey(@PathVariable Long userId) {
        return signKeyRepository.findByUserId(userId)
                .map(k -> {
                    PublicKey dto = new PublicKey();
                    dto.setUserId(k.getUserId());
                    dto.setPublicKey(k.getPublicKey());
                    dto.setCreatedAt(k.getCreatedAt());
                    return ResponseEntity.ok(dto);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<PublicKey> saveSignKey(@RequestBody PublicKey request) {
        if (request.getUserId() == null || request.getPublicKey() == null || request.getPublicKey().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        SignKey key = signKeyRepository.findByUserId(request.getUserId()).orElseGet(SignKey::new);
        key.setUserId(request.getUserId());
        key.setPublicKey(request.getPublicKey());
        key.setCreatedAt(ZonedDateTime.now());
        SignKey saved = signKeyRepository.save(key);
        System.out.println("Sign key saved for userId: " + saved.getUserId());

        PublicKey dto = new PublicKey();
        dto.setUserId(saved.getUserId());
        dto.setPublicKey(saved.getPublicKey());
        dto.setCreatedAt(saved.getCreatedAt());
        return ResponseEntity.ok(dto);
    }
}

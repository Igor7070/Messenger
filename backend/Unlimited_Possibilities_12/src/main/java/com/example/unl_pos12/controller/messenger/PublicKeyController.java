package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.PublicKey;
import com.example.unl_pos12.repo.PublicKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public_keys")
public class PublicKeyController {
    @Autowired
    private PublicKeyRepository publicKeyRepository;

    @GetMapping("/{userId}")
    public ResponseEntity<PublicKey> getPublicKey(@PathVariable Long userId) {
        PublicKey publicKey = publicKeyRepository.findByUserId(userId);
        if (publicKey != null) {
            return ResponseEntity.ok(publicKey);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public PublicKey savePublicKey(@RequestBody PublicKey publicKey) {
        return publicKeyRepository.save(publicKey);
    }
}

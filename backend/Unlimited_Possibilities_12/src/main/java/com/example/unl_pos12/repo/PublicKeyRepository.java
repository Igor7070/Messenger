package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.PublicKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicKeyRepository extends JpaRepository<PublicKey, Long> {
    PublicKey findByUserId(Long userId);
}

package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.PublicKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicKeyRepository extends JpaRepository<PublicKey, Long> {
    PublicKey findByUserId(Long userId);

    /** Ключи удалённой учётки: иначе остаются в базе навсегда. */
    void deleteByUserId(Long userId);
}

package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.SignKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SignKeyRepository extends JpaRepository<SignKey, Long> {
    Optional<SignKey> findByUserId(Long userId);
}

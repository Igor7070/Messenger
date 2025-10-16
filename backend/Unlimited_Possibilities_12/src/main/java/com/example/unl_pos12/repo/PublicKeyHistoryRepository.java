package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.PublicKeyHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PublicKeyHistoryRepository extends JpaRepository<PublicKeyHistory, Long> {
    PublicKeyHistory findByUserIdAndValidUntilIsNull(Long userId);
    Optional<PublicKeyHistory> findById(Long id); // [CHANGE] Изменено с PublicKeyHistory на Optional<PublicKeyHistory>
}

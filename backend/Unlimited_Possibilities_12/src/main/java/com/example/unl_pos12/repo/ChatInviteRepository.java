package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.ChatInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatInviteRepository extends JpaRepository<ChatInvite, Long> {

    Optional<ChatInvite> findByToken(String token);

    List<ChatInvite> findByChatIdOrderByCreatedAtDesc(Long chatId);

    void deleteByChatId(Long chatId);
}

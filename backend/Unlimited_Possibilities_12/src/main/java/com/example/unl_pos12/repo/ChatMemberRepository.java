package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.ChatMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMemberRepository extends JpaRepository<ChatMember, Long> {

    List<ChatMember> findByChatIdOrderByJoinedAtAsc(Long chatId);

    Optional<ChatMember> findByChatIdAndUserId(Long chatId, Long userId);

    boolean existsByChatIdAndUserId(Long chatId, Long userId);

    List<ChatMember> findByUserId(Long userId);

    List<ChatMember> findByChatIdAndRole(Long chatId, ChatMember.Role role);

    long countByChatId(Long chatId);

    void deleteByChatId(Long chatId);
}

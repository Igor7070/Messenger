package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findBySender(User sender);
    List<Message> findByContentAndSenderIdAndChatId(String content, Long senderId, Long chatId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Message m WHERE m.chat.id = :chatId")
    void deleteByChatId(@Param("chatId") Long chatId);
}

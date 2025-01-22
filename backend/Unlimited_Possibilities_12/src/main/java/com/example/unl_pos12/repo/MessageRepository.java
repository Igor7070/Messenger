package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findBySender(User sender);
    List<Message> findByContentAndSenderIdAndChatId(String content, Long senderId, Long chatId);
}

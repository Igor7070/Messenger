package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.Message;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {
}

package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.Chat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatRepository extends JpaRepository<Chat, Long> {
    Chat findByName(String name);
    List<Chat> findByNameIn(List<String> names);
    Chat findTopByOrderByIdDesc(); // Возвращает последний чат по ID
}

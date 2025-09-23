package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.Call;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallRepository extends JpaRepository<Call, Long> {
    List<Call> findByUserId(Long userId); // Поиск звонков по ID пользователя
}

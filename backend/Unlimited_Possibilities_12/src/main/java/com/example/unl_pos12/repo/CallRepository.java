package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.Call;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CallRepository extends JpaRepository<Call, Long> {
    List<Call> findByUserId(Long userId); // Поиск звонков по ID пользователя

    // Проверка существования звонка по userId, roomId и временному диапазону
    @Query("SELECT COUNT(c) > 0 FROM Call c WHERE c.user.id = :userId AND c.roomId = :roomId AND c.timestamp BETWEEN :start AND :end")
    boolean existsByUserIdAndRoomIdAndTimestampBetween(Long userId, String roomId, LocalDateTime start, LocalDateTime end);

    @Modifying
    @Query("DELETE FROM Call c WHERE c.timestamp < :threshold")
    void deleteCallsOlderThan(LocalDateTime threshold);
}
